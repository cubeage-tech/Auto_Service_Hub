package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.MechanicAssignmentRequestDTO;
import com.autoservicehub.dto.MechanicAssignmentResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.MechanicAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Mechanic Assignment implementation (SRS Section 5.4, FR-AI-17..20).
 *
 * <p><strong>SRS BR-09 compliance:</strong> this service performs NO
 * assignment. {@code JobCard.mechanic} is never written, no job task is
 * created, and no notification is sent. The response is advisory output that a
 * service advisor or manager must review and act on themselves.
 *
 * <p><strong>No fabricated mechanics.</strong> The candidate pool is built from
 * real {@code Mechanic} rows in ACTIVE status, and any mechanic the provider
 * names is validated against that pool. A provider-supplied id that does not
 * correspond to a real, active candidate is discarded rather than passed
 * through, and no mechanic is ever selected as a fallback.
 *
 * <p><strong>No fabricated capability data.</strong> The current data model has
 * no usable mechanic skill, certification, rating, availability or attendance
 * records — {@code MechanicSkill} and {@code Attendance} have no mechanic FK at
 * all, and no rating or shift table exists. These gaps are reported in
 * {@code dataLimitations} rather than being simulated. The only workload signal
 * available is a real count of each mechanic's currently open job cards.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MechanicAssignmentServiceImpl implements MechanicAssignmentService {

    /** Below this confidence the recommendation is flagged for closer manual review. */
    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.50");

    /** Employment status that makes a mechanic eligible for assignment. */
    static final String ACTIVE_STATUS = "ACTIVE";

    /** Terminal job card status; a mechanic holding these is not "busy". */
    static final String DELIVERED_STATUS = "DELIVERED";

    public static final String DISCLAIMER =
            "This mechanic assignment is AI-generated and is a RECOMMENDATION ONLY. No assignment has been " +
          "made: the job card has not been modified and no schedule entry has been created. A service " +
          "advisor or manager must review this recommendation and assign the work manually. The system " +
          "holds no mechanic skill, certification, rating, availability or attendance data, so this " +
          "recommendation is based on employment status and current workload only. " +
          "SmartGarage AI accepts no liability for staffing decisions based solely on this output.";

    private final AiOrchestrationService aiOrchestrationService;
    private final JobCardRepository      jobCardRepository;
    private final MechanicRepository     mechanicRepository;

    @Override
    public MechanicAssignmentResponseDTO recommend(MechanicAssignmentRequestDTO request) {

        // ── 1. Validation ─────────────────────────────────────────────────
        // @NotNull/@Positive on jobCardId are enforced by Bean Validation.
        Long jobCardId = request.getJobCardId();
        if (jobCardId == null) {
            throw new BusinessRuleException(
                    "jobCardId is required to recommend a mechanic for a job.");
        }

        // ── 2. Resolve the job card ───────────────────────────────────────
        JobCard jobCard = jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));

        // ── 3. Build the real candidate pool ──────────────────────────────
        List<String> limitations = describeDataModelGaps();

        List<Mechanic> candidates = resolveCandidates(request, limitations);
        List<MechanicAssignmentResponseDTO.CandidateDTO> candidateViews =
                candidates.stream().map(this::toCandidateView).toList();

        MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO poolSummary =
                new MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO(
                        candidateViews.size(),
                        jobCard.getStatus(),
                        jobCard.getMechanic() != null);

        if (candidates.isEmpty()) {
            limitations.add("No active mechanics are available, so no assignment could be recommended. " +
                    "Please assign the work manually.");
            return buildNoRecommendationResponse(jobCardId, poolSummary, null, limitations);
        }
        if (jobCard.getMechanic() != null) {
            limitations.add("This job card is already assigned to a mechanic. Any recommendation is " +
                    "informational only.");
        }

        // ── 4. Build the provider request ────────────────────────────────
        Map<String, Object> context =
                buildContext(request, jobCard, candidateViews, limitations);

        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.MECHANIC_ASSIGNMENT);
        aiRequest.setJobCardId(jobCardId);
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
            limitations.add("The AI provider is unavailable, so no mechanic could be recommended. " +
                    "Please assign the work manually.");
            return buildNoRecommendationResponse(jobCardId, poolSummary, null, limitations);
        }

        // ── 6. Map and VALIDATE the provider's recommendation ────────────
        return mapToResponse(jobCardId, result, candidates, poolSummary, limitations);
    }

    /**
     * The data the assignment decision would ideally use but which the current
     * model does not hold. Stated plainly rather than approximated.
     */
    private List<String> describeDataModelGaps() {
        List<String> gaps = new ArrayList<>();
        gaps.add("Mechanic skill and certification data is not usable in the current model " +
                 "(mechanic_skills has no link to a mechanic), so skill fit could not be assessed.");
        gaps.add("No mechanic rating, availability schedule or attendance data is available, so " +
                 "performance and availability could not be considered.");
        gaps.add("Workload is measured only as a count of currently open job cards; shift hours, " +
                 "capacity and part-time hours are not modelled.");
        return gaps;
    }

    /**
     * Resolves the eligible mechanics. When the caller narrows the pool, each
     * requested id must resolve to a real ACTIVE mechanic — anything else is a
     * 404 rather than being silently dropped or substituted.
     */
    private List<Mechanic> resolveCandidates(MechanicAssignmentRequestDTO request,
                                             List<String> limitations) {
        List<Long> requested = request.getCandidateMechanicIds();
        if (requested == null || requested.isEmpty()) {
            return mechanicRepository.findByStatusIgnoreCase(ACTIVE_STATUS);
        }
        // NB: do not use requested.contains(null) — immutable lists throw NPE.
        if (requested.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessRuleException("candidateMechanicIds must all be positive numbers.");
        }
        List<Mechanic> candidates = new ArrayList<>();
        for (Long id : requested) {
            Mechanic mechanic = mechanicRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + id));
            if (!ACTIVE_STATUS.equalsIgnoreCase(mechanic.getStatus())) {
                throw new BusinessRuleException(
                        "Mechanic " + id + " is not active and cannot be assigned work.");
            }
            candidates.add(mechanic);
        }
        return candidates;
    }

    /**
     * Builds a candidate view with a REAL open-workload count. This is a count
     * of job cards, not an estimate of hours or capacity.
     */
    private MechanicAssignmentResponseDTO.CandidateDTO toCandidateView(Mechanic mechanic) {
        long openJobCards = jobCardRepository.countByMechanicIdAndStatusNot(
                mechanic.getId(), DELIVERED_STATUS);
        return new MechanicAssignmentResponseDTO.CandidateDTO(
                mechanic.getId(),
                mechanic.getEmployeeCode(),
                mechanic.getName(),
                mechanic.getExperienceYears(),
                openJobCards);
    }

    /**
     * Assembles the provider context from real database values only. Customer
     * PII (name, phone, email, address) is deliberately never included. Mechanic
     * names are staff, not customers, and are included so the provider can
     * produce a readable rationale.
     */
    private Map<String, Object> buildContext(
            MechanicAssignmentRequestDTO request,
            JobCard jobCard,
            List<MechanicAssignmentResponseDTO.CandidateDTO> candidates,
            List<String> limitations) {

        Map<String, Object> ctx = new LinkedHashMap<>();

        // Job card facts
        putIfNotNull(ctx, "jobCardNumber", jobCard.getJobCardNumber());
        putIfNotNull(ctx, "jobStatus",     jobCard.getStatus());
        putIfNotNull(ctx, "serviceType",   jobCard.getServiceType());
        putIfNotNull(ctx, "complaint",     jobCard.getComplaint());
        putIfNotNull(ctx, "technicianNotes", jobCard.getTechnicianNotes());
        putIfNotNull(ctx, "assignedDate",  jobCard.getAssignedDate());
        putIfNotNull(ctx, "estimatedDelivery", jobCard.getEstimatedDelivery());

        // Vehicle context, if the job card has one — no customer identity.
        Vehicle vehicle = jobCard.getVehicle();
        if (vehicle != null) {
            putIfNotNull(ctx, "vehicleMake",    vehicle.getMake());
            putIfNotNull(ctx, "vehicleModel",   vehicle.getModel());
            putIfNotNull(ctx, "vehicleVariant", vehicle.getVariant());
            putIfNotNull(ctx, "vehicleYear",    vehicle.getYear());
            putIfNotNull(ctx, "vehicleMileage", vehicle.getMileage());
        } else {
            limitations.add("This job card has no vehicle linked, so vehicle context was not available.");
        }

        if (isNotBlank(request.getAdditionalContext())) {
            ctx.put("additionalContext", request.getAdditionalContext().trim());
            limitations.add("The requested skill or certification could not be verified against any " +
                    "system data, so it was passed to the provider as unverified context only.");
        }

        ctx.put("candidates", candidates);
        ctx.put("candidateCount", candidates.size());

        // Ask for one recommendation naming a real candidate mechanicId.
        ctx.put("requestedOutput",
                "Return a recommendedMechanicId chosen from the candidates listed (or omit it if none is " +
              "suitable), a rationale explaining the choice, and a confidence value between 0 and 1. " +
              "Only employment status, experience and open workload are available — acknowledge that skill, " +
              "ratings and availability could not be considered.");

        return ctx;
    }

    /**
     * Provider availability, using the same signal convention as the other
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

    private MechanicAssignmentResponseDTO buildNoRecommendationResponse(
            Long jobCardId,
            MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO poolSummary,
            BigDecimal confidence,
            List<String> limitations) {
        return MechanicAssignmentResponseDTO.builder()
                .jobCardId(jobCardId)
                .recommendedMechanicId(null)            // no mechanic guessed
                .recommendedMechanicEmployeeCode(null)
                .recommendedMechanicName(null)
                .rationale(null)
                .confidence(confidence)
                .humanReviewRequired(true)
                .assignmentPersisted(false)              // never assigned
                .providerUnavailable(confidence == null)
                .dataLimitations(limitations)
                .candidatePoolSummary(poolSummary)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private MechanicAssignmentResponseDTO mapToResponse(
            Long jobCardId,
            AiResult result,
            List<Mechanic> candidates,
            MechanicAssignmentResponseDTO.CandidatePoolSummaryDTO poolSummary,
            List<String> limitations) {

        BigDecimal confidence = result.getConfidence();
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            limitations.add("The AI provider returned low confidence for this recommendation. A manual " +
                    "review is strongly recommended before acting on it.");
        }

        // Validate the provider's pick against the real candidate pool.
        Long suggestedId = toLong(result.getDetails() == null ? null : result.getDetails().get("recommendedMechanicId"));
        Mechanic recommended = null;
        if (suggestedId == null) {
            limitations.add("The AI provider did not recommend a mechanic. Please assign the work manually.");
        } else {
            Optional<Mechanic> match = candidates.stream()
                    .filter(m -> m.getId().equals(suggestedId))
                    .findFirst();
            if (match.isPresent()) {
                recommended = match.get();
            } else {
                // A provider-supplied id that is not a real, active candidate is
                // discarded — never passed through and never substituted.
                limitations.add("The mechanic suggested by the AI provider (id " + suggestedId + ") is not in " +
                        "the eligible candidate pool, so the recommendation was discarded. Please assign " +
                        "the work manually.");
            }
        }

        Object rawRationale = result.getDetails() == null
                ? null : result.getDetails().get("rationale");

        return MechanicAssignmentResponseDTO.builder()
                .jobCardId(jobCardId)
                .recommendedMechanicId(recommended == null ? null : recommended.getId())
                .recommendedMechanicEmployeeCode(recommended == null ? null : recommended.getEmployeeCode())
                .recommendedMechanicName(recommended == null ? null : recommended.getName())
                .rationale(recommended == null ? null : toText(rawRationale))
                .confidence(confidence)
                .humanReviewRequired(true)      // always true — SRS BR-09 / FR-AI-20
                .assignmentPersisted(false)     // recommendation only — nothing is written
                .providerUnavailable(false)
                .dataLimitations(limitations)
                .candidatePoolSummary(poolSummary)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
