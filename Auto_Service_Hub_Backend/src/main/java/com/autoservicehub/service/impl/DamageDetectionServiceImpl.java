package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.DamageDetectionRequestDTO;
import com.autoservicehub.dto.DamageDetectionResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.DamageDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Damage Detection implementation (SRS Section 5.6, FR-AI-13..16).
 *
 * <p><strong>Text-based advisory assessment. No image is analysed.</strong> The
 * current backend has no image upload, storage or vision capability, so this
 * service reasons over a written description plus real vehicle and job card
 * context. The disclaimer and limitations state this explicitly so the output
 * can never be mistaken for a photo analysis.
 *
 * <p><strong>SRS BR-09 compliance:</strong> this service never writes an
 * inspection, job card, estimate or invoice. The assessment is advisory output
 * that a qualified technician must confirm by physically inspecting the vehicle.
 *
 * <p><strong>Nothing is fabricated.</strong> A damage area or severity the
 * provider does not supply stays {@code null} — severity in particular is never
 * defaulted, because the system defines no severity scale to default to. When the
 * provider is unavailable, no damage findings are produced at all.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DamageDetectionServiceImpl implements DamageDetectionService {

    /** Below this confidence the assessment is flagged for closer manual review. */
    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.50");

    /** Minimum description length that carries enough signal to assess. */
    static final int MIN_DESCRIPTION_LENGTH = 10;

    public static final String DISCLAIMER =
            "This damage assessment is AI-generated from a WRITTEN description of the damage and is " +
          "advisory only. NO photographs, images or video were analysed — the system has no image upload " +
          "or vision capability. A qualified technician must physically inspect the vehicle and confirm " +
          "every reported area before this assessment is acted on or shown to a customer. This output " +
          "must NOT be used to settle, deny or value an insurance claim, and must NOT be used to declare " +
          "a vehicle safe or unsafe, roadworthy or not roadworthy. SmartGarage AI accepts no liability " +
          "for damage or insurance decisions based solely on this output.";

    private final AiOrchestrationService aiOrchestrationService;
    private final VehicleRepository      vehicleRepository;
    private final JobCardRepository      jobCardRepository;

    @Override
    public DamageDetectionResponseDTO assess(DamageDetectionRequestDTO request) {

        // ── 1. Validation ─────────────────────────────────────────────────
        // @NotNull/@Positive on vehicleId and @NotBlank on damageDescription are
        // enforced by Bean Validation. These checks guard the service layer.
        Long vehicleId = request.getVehicleId();
        if (vehicleId == null) {
            throw new BusinessRuleException(
                    "vehicleId is required to assess damage for a vehicle.");
        }

        String description = request.getDamageDescription() == null
                ? "" : request.getDamageDescription().trim();
        if (description.length() < MIN_DESCRIPTION_LENGTH) {
            throw new BusinessRuleException(
                    "damageDescription is too short to assess. Please describe the reported damage in at " +
                  "least " + MIN_DESCRIPTION_LENGTH + " characters.");
        }

        // ── 2. Resolve the vehicle ────────────────────────────────────────
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehicleId));

        // ── 3. Mandatory limitations, then optional context ───────────────
        List<String> limitations = mandatoryLimitations();

        JobCard jobCard = resolveJobCard(request.getJobCardId(), vehicle, limitations);

        if (isNotBlank(request.getInspectionFindings())) {
            limitations.add("Inspection findings were supplied as free text by the caller. They were not " +
                    "read from the inspections table, because inspection records are not linked to a " +
                    "vehicle or job card.");
        }

        // ── 4. Build the provider request ────────────────────────────────
        Map<String, Object> context = buildContext(request, vehicle, jobCard);

        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.DAMAGE_DETECTION);
        aiRequest.setVehicleId(vehicleId);
        aiRequest.setJobCardId(request.getJobCardId());
        aiRequest.setContext(context);

        // ── 5. Invoke the AI — degrade safely when unavailable ────────────
        AiResult result;
        boolean providerUnavailable;
        try {
            result = aiOrchestrationService.process(aiRequest);
            providerUnavailable = isProviderUnavailable(result);
        } catch (Exception ex) {
            // Swallow provider/network/timeout failures. Never surface provider
            // internals (keys, endpoints, error text) to the caller.
            providerUnavailable = true;
            result = null;
        }

        if (providerUnavailable) {
            limitations.add("The AI provider is unavailable, so no damage assessment could be produced. " +
                    "Please inspect the vehicle and record the damage manually.");
            return buildUnavailableResponse(vehicleId, request.getJobCardId(), limitations);
        }

        // ── 6. Map the provider's output, fabricating nothing ─────────────
        return mapToResponse(vehicleId, request.getJobCardId(), result, limitations);
    }

    /**
     * The limitations that are always true of this feature, because of what the
     * system does not have. Stated on every response rather than buried.
     */
    private List<String> mandatoryLimitations() {
        List<String> gaps = new ArrayList<>();
        gaps.add("Damage assessment is based on the reported description and job context only. No images " +
                 "or photographs were analysed — the system has no image upload or vision capability.");
        gaps.add("Inspections are not linked to vehicles or job cards in the current data model, so " +
                 "inspection records could not be consulted.");
        gaps.add("No damage type taxonomy or severity scale is defined in the system, so provider " +
                 "severity levels could not be validated against a standard.");
        return gaps;
    }

    /**
     * Resolves the job card and, where the existing model allows it, verifies the
     * job card actually belongs to the requested vehicle. A mismatch is a
     * business-rule violation rather than a silent cross-vehicle context mix-up.
     */
    private JobCard resolveJobCard(Long jobCardId, Vehicle vehicle, List<String> limitations) {
        if (jobCardId == null) {
            limitations.add("No job card was supplied, so the recorded service type, complaint and " +
                    "technician notes were not considered.");
            return null;
        }
        JobCard jobCard = jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));

        if (jobCard.getVehicle() != null && vehicle.getId() != null
                && !vehicle.getId().equals(jobCard.getVehicle().getId())) {
            throw new BusinessRuleException(
                    "JobCard " + jobCardId + " belongs to a different vehicle and cannot be used to " +
                  "assess damage for vehicle " + vehicle.getId() + ".");
        }
        return jobCard;
    }

    /**
     * Assembles the provider context from real database values only. Customer PII
     * (name, phone, email, address) is deliberately never included. No image
     * reference of any kind is included, because none can be validated.
     */
    private Map<String, Object> buildContext(DamageDetectionRequestDTO request,
                                             Vehicle vehicle,
                                             JobCard jobCard) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // The written report of the damage — the primary input.
        ctx.put("damageDescription", request.getDamageDescription().trim());
        ctx.put("assessmentType", "text-based description assessment");
        ctx.put("imageAnalysisPerformed", false);   // explicit: no image was analysed

        if (isNotBlank(request.getInspectionFindings())) {
            ctx.put("inspectionFindings", request.getInspectionFindings().trim());
        }

        // Vehicle context (from DB)
        putIfNotNull(ctx, "vehicleMake",     vehicle.getMake());
        putIfNotNull(ctx, "vehicleModel",    vehicle.getModel());
        putIfNotNull(ctx, "vehicleVariant",  vehicle.getVariant());
        putIfNotNull(ctx, "vehicleYear",     vehicle.getYear());
        putIfNotNull(ctx, "vehicleMileage",  vehicle.getMileage());
        putIfNotNull(ctx, "registrationNo",  vehicle.getRegistrationNo());

        // Job card context (from DB, only when supplied and matched to this vehicle)
        if (jobCard != null) {
            putIfNotNull(ctx, "jobCardNumber", jobCard.getJobCardNumber());
            putIfNotNull(ctx, "jobStatus",     jobCard.getStatus());
            putIfNotNull(ctx, "serviceType",   jobCard.getServiceType());
            putIfNotNull(ctx, "complaint",     jobCard.getComplaint());
            putIfNotNull(ctx, "technicianNotes", jobCard.getTechnicianNotes());
        }

        ctx.put("requestedOutput",
                "Return a summary and affectedAreas as an array of {area, severity, description, " +
              "rationale}, based ONLY on the written damageDescription. Omit severity entirely if you cannot " +
              "support it rather than guessing. Also return a confidence between 0 and 1. This is a " +
              "text-based assessment: no images are available and none may be assumed or referenced.");

        return ctx;
    }

    /**
     * Provider availability, using the same signal convention as the other five
     * completed AI services: zero confidence combined with a
     * human-confirmation flag signals unavailability, as does a null confidence.
     */
    private boolean isProviderUnavailable(AiResult result) {
        if (result == null || result.getConfidence() == null) {
            return true;
        }
        return result.getConfidence().compareTo(BigDecimal.ZERO) == 0
                && result.isRequiresHumanConfirmation();
    }

    private DamageDetectionResponseDTO buildUnavailableResponse(
            Long vehicleId, Long jobCardId, List<String> limitations) {
        return DamageDetectionResponseDTO.builder()
                .vehicleId(vehicleId)
                .jobCardId(jobCardId)
                .summary(null)                  // never fabricate a damage finding
                .affectedAreas(List.of())       // no damage areas invented
                .confidence(null)
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(limitations)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private DamageDetectionResponseDTO mapToResponse(
            Long vehicleId, Long jobCardId, AiResult result, List<String> limitations) {

        List<DamageDetectionResponseDTO.DamageAreaDTO> areas = extractAreas(result);
        BigDecimal confidence = result.getConfidence();

        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            limitations.add("The AI provider returned low confidence for this assessment. A physical " +
                    "inspection by a qualified technician is strongly recommended.");
        }
        if (areas.isEmpty()) {
            limitations.add("The AI provider returned no damage areas that could be derived from the " +
                    "written description. Please inspect the vehicle and record the damage manually.");
        }

        return DamageDetectionResponseDTO.builder()
                .vehicleId(vehicleId)
                .jobCardId(jobCardId)
                .summary(toText(result.getSummary()))
                .affectedAreas(areas)
                .confidence(confidence)
                .humanReviewRequired(true)      // always true — SRS BR-09 / FR-AI-16
                .providerUnavailable(false)
                .dataLimitations(limitations)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Parses the provider's damage areas. A field the provider did not supply
     * stays {@code null} — severity in particular is never defaulted, because
     * the system defines no severity scale. Entries with no area name are
     * skipped rather than being emitted as empty findings.
     */
    private List<DamageDetectionResponseDTO.DamageAreaDTO> extractAreas(AiResult result) {
        List<DamageDetectionResponseDTO.DamageAreaDTO> areas = new ArrayList<>();
        if (result.getDetails() == null) {
            return areas;
        }
        Object raw = result.getDetails().get("affectedAreas");
        if (!(raw instanceof List<?> rawList)) {
            return areas;
        }
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String area = toText(map.get("area"));
            if (area == null) {
                continue;   // no area name — not a usable finding
            }
            areas.add(new DamageDetectionResponseDTO.DamageAreaDTO(
                    area,
                    normaliseSeverity(toText(map.get("severity"))),  // null when not supplied
                    toText(map.get("description")),
                    toText(map.get("rationale"))));
        }
        return areas;
    }

    /**
     * Normalises common severity wording for display. An unrecognised or absent
     * value is returned as-is (or null) — never coerced into a severity the
     * provider did not state.
     */
    private String normaliseSeverity(String severity) {
        if (severity == null) {
            return null;
        }
        return switch (severity.trim().toUpperCase()) {
            case "MINOR", "LOW", "SLIGHT"          -> "MINOR";
            case "MODERATE", "MEDIUM"              -> "MODERATE";
            case "MAJOR", "HIGH", "SIGNIFICANT"    -> "MAJOR";
            case "SEVERE", "CRITICAL", "TOTAL"     -> "SEVERE";
            default                                -> severity.trim().toUpperCase();
        };
    }

    private String toText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
