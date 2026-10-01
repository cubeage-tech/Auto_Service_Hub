package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.DiagnosisRequestDTO;
import com.autoservicehub.dto.DiagnosisResponseDTO;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.VehicleDiagnosisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Vehicle Diagnosis implementation (SRS Section 5.1, FR-AI-01..04).
 *
 * <p><strong>SRS BR-09 compliance:</strong> This service NEVER creates or modifies
 * a job card, inspection record, estimate, or any other business entity.
 * The diagnosis result is advisory only. Human staff must review and confirm
 * before any repair action is taken.
 *
 * <p><strong>Provider resilience:</strong> If the AI provider is unavailable
 * (including the default {@code NoopAiProviderClient}), the service returns a
 * clearly-flagged response with {@code providerUnavailable = true} rather than
 * propagating an exception to the caller.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VehicleDiagnosisServiceImpl implements VehicleDiagnosisService {

    public static final String DISCLAIMER =
            "This diagnosis is AI-generated and must be reviewed and confirmed by a " +
            "qualified technician before any repair work is carried out. " +
            "SmartGarage AI accepts no liability for actions taken based solely on this output.";

    private final AiOrchestrationService aiOrchestrationService;
    private final VehicleRepository      vehicleRepository;

    @Override
    public DiagnosisResponseDTO diagnose(DiagnosisRequestDTO request) {

        // ── 1. Domain validation ──────────────────────────────────────────
        // @NotBlank on symptoms is enforced by Bean Validation before we reach here.
        // Additional rule: at least one of symptoms or inspectionFindings must be
        // non-trivially short so the AI has enough signal.
        String symptoms = request.getSymptoms() == null ? "" : request.getSymptoms().trim();
        if (symptoms.length() < 5) {
            throw new BusinessRuleException(
                    "symptoms is too short to produce a useful diagnosis. " +
                    "Please provide at least 5 characters describing the reported issue.");
        }

        // ── 2. Enrich context with vehicle data ───────────────────────────
        Vehicle vehicle = null;
        if (request.getVehicleId() != null) {
            vehicle = vehicleRepository.findById(request.getVehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Vehicle not found: " + request.getVehicleId()));
        }

        Map<String, Object> context = buildContext(request, vehicle);

        // ── 3. Build AiRequest ────────────────────────────────────────────
        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.VEHICLE_DIAGNOSIS);
        aiRequest.setVehicleId(request.getVehicleId());
        aiRequest.setJobCardId(request.getJobCardId());
        aiRequest.setContext(context);

        // ── 4. Invoke AI — handle provider unavailability gracefully ──────
        AiResult result;
        boolean providerUnavailable = false;
        try {
            result = aiOrchestrationService.process(aiRequest);
            // The NoopAiProviderClient signals unavailability via a zero-confidence
            // result with requiresHumanConfirmation=true and a known summary message.
            // NoopAiProviderClient signals unavailability:
            // confidence == 0 AND requiresHumanConfirmation == true
            if (result.getConfidence() != null
                    && result.getConfidence().compareTo(BigDecimal.ZERO) == 0
                    && result.isRequiresHumanConfirmation()) {
                providerUnavailable = true;
            }
        } catch (Exception ex) {
            // Catch any unchecked exception from the provider (timeout, network error, etc.)
            // and degrade gracefully — never expose provider details to the client.
            providerUnavailable = true;
            result = buildFallbackResult();
        }

        // ── 5. Map to typed response ──────────────────────────────────────
        return mapToResponse(request.getVehicleId(), result, providerUnavailable);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Assembles the key/value context map that travels to the AI provider.
     * Only fields that are present and non-blank are included; no invented data.
     */
    private Map<String, Object> buildContext(DiagnosisRequestDTO request, Vehicle vehicle) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Vehicle info (from DB — only when vehicleId was supplied)
        if (vehicle != null) {
            if (vehicle.getMake()  != null) ctx.put("vehicleMake",    vehicle.getMake());
            if (vehicle.getModel() != null) ctx.put("vehicleModel",   vehicle.getModel());
            if (vehicle.getVariant() != null) ctx.put("vehicleVariant", vehicle.getVariant());
            if (vehicle.getYear()  != null) ctx.put("vehicleYear",    vehicle.getYear());
            if (vehicle.getMileage() != null) ctx.put("vehicleMileage", vehicle.getMileage());
            if (vehicle.getRegistrationNo() != null)
                ctx.put("registrationNo", vehicle.getRegistrationNo());
        }

        // Diagnostic inputs from the request
        ctx.put("symptoms", request.getSymptoms().trim());

        if (request.getInspectionFindings() != null
                && !request.getInspectionFindings().isBlank()) {
            ctx.put("inspectionFindings", request.getInspectionFindings().trim());
        }
        if (request.getTechnicianNotes() != null
                && !request.getTechnicianNotes().isBlank()) {
            ctx.put("technicianNotes", request.getTechnicianNotes().trim());
        }

        return ctx;
    }

    /**
     * Maps an {@link AiResult} to the typed {@link DiagnosisResponseDTO},
     * enforcing the human-review flag unconditionally.
     */
    private DiagnosisResponseDTO mapToResponse(Long vehicleId,
                                               AiResult result,
                                               boolean providerUnavailable) {
        // Extract possibleIssue and recommendation from the result's details map
        // (populated by real provider implementations) or fall back to the summary.
        String possibleIssue   = extractDetail(result, "possibleIssue",   result.getSummary());
        String recommendation  = extractDetail(result, "recommendation",  "Please have a qualified technician inspect the vehicle.");

        return DiagnosisResponseDTO.builder()
                .vehicleId(vehicleId)
                .possibleIssue(possibleIssue)
                .recommendation(recommendation)
                .confidence(result.getConfidence())
                .disclaimer(DISCLAIMER)
                .humanReviewRequired(true)          // always true — SRS BR-09 / FR-AI-04
                .providerUnavailable(providerUnavailable)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private String extractDetail(AiResult result, String key, String fallback) {
        if (result.getDetails() != null) {
            Object val = result.getDetails().get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return fallback;
    }

    private AiResult buildFallbackResult() {
        return new AiResult(
                AiFeatureType.VEHICLE_DIAGNOSIS,
                "AI provider is currently unavailable. Please proceed with manual inspection.",
                BigDecimal.ZERO,
                Map.of(),
                true
        );
    }
}
