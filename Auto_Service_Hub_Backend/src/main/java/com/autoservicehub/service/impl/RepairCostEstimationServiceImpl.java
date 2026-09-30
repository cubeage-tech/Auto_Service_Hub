package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.RepairCostEstimationRequestDTO;
import com.autoservicehub.dto.RepairCostEstimationResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.RepairCostEstimationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Repair Cost Estimation implementation (SRS Section 5.2, FR-AI-05..08).
 *
 * <p><strong>SRS BR-09 compliance:</strong> this service never writes an
 * estimate, invoice, job card, task or stock movement. The returned figures are
 * advisory display values only. A human must review the estimate and produce a
 * proper estimate document before the customer is charged.
 *
 * <p><strong>No client totals are trusted:</strong> the request DTO has no
 * price/total field by design, so an estimate can never be injected by a client.
 *
 * <p><strong>No invented domain data:</strong> only relationships that exist in
 * the current JPA model are traversed. Known modelling gaps are surfaced in
 * {@code dataLimitations} rather than papered over.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepairCostEstimationServiceImpl implements RepairCostEstimationService {

    /** Below this confidence the estimate is flagged for closer manual review. */
    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.50");

    /**
     * Currency reported for all monetary values. No currency column exists in
     * the current domain model, so this is an application-level constant that
     * is surfaced on every response rather than a per-record value.
     */
    static final String DEFAULT_CURRENCY = "INR";

    public static final String DISCLAIMER =
            "This cost estimate is AI-generated and is an approximation only. It is NOT a quotation "
          + "and NOT a guaranteed price. It must be reviewed and confirmed by a service advisor or "
          + "manager, and agreed with the customer, before any repair work is authorised. "
          + "Final pricing is always established on the formal estimate or invoice document. "
          + "SmartGarage AI accepts no liability for costs quoted on the basis of this output.";

    private final AiOrchestrationService aiOrchestrationService;
    private final VehicleRepository      vehicleRepository;
    private final JobCardRepository      jobCardRepository;
    private final PartRepository         partRepository;

    @Override
    public RepairCostEstimationResponseDTO estimate(RepairCostEstimationRequestDTO request) {

        // ── 1. Domain validation ──────────────────────────────────────────
        // @NotBlank on serviceDescription is enforced by Bean Validation before
        // we get here. This rule guards against a description that is present
        // but too thin to cost anything against.
        String serviceDescription = request.getServiceDescription() == null
                ? "" : request.getServiceDescription().trim();
        if (serviceDescription.length() < 5) {
            throw new BusinessRuleException(
                    "serviceDescription is too short to produce a meaningful cost estimate. "
                  + "Please describe the repair work in at least 5 characters.");
        }

        // ── 2. Resolve domain context (existing relationships only) ───────
        List<String> limitations = new ArrayList<>();
        Vehicle vehicle = resolveVehicle(request.getVehicleId(), limitations);
        JobCard jobCard = resolveJobCard(request.getJobCardId(), limitations);
        List<Part> parts = resolveParts(request.getPartIds());

        if (parts.isEmpty()) {
            limitations.add("No parts were supplied, so the parts component of this estimate is not "
                    + "grounded in inventory pricing.");
        }
        limitations.add("The system has no labour-rate table, so labour cost is not computed "
                + "authoritatively; any labour figure is indicative only.");

        // ── 3. Build the provider request ────────────────────────────────
        Map<String, Object> context = buildContext(request, vehicle, jobCard, parts);
        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.REPAIR_COST_ESTIMATE);
        aiRequest.setVehicleId(request.getVehicleId());
        aiRequest.setJobCardId(request.getJobCardId());
        aiRequest.setContext(context);

        // ── 4. Invoke the AI — degrade safely when unavailable ────────────
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
            limitations.add("The AI provider is unavailable, so no cost figure could be produced. "
                    + "Please prepare a manual estimate.");
            return buildUnavailableResponse(request, limitations);
        }

        // ── 5. Map provider output to the typed response ─────────────────
        return mapToResponse(request, result, limitations);
    }

    // ── Domain resolution ─────────────────────────────────────────────────

    private Vehicle resolveVehicle(Long vehicleId, List<String> limitations) {
        if (vehicleId == null) {
            limitations.add("No vehicle was supplied, so make, model, year and mileage were not "
                    + "considered when producing this estimate.");
            return null;
        }
        return vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehicleId));
    }

    private JobCard resolveJobCard(Long jobCardId, List<String> limitations) {
        if (jobCardId == null) {
            limitations.add("No job card was supplied, so the recorded service type and technician "
                    + "notes were not considered when producing this estimate.");
            return null;
        }
        return jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));
    }

    /**
     * Resolves explicitly requested parts. The domain model has no link between
     * parts and job cards, so parts cannot be derived from a job card and must
     * be named by the caller.
     */
    private List<Part> resolveParts(List<Long> partIds) {
        if (partIds == null || partIds.isEmpty()) {
            return List.of();
        }
        // NB: do NOT use partIds.contains(null) — immutable lists (List.of) throw
        // NullPointerException on a null probe, which would surface as a 500.
        boolean invalid = partIds.stream()
                .anyMatch(id -> id == null || id <= 0);
        if (invalid) {
            throw new BusinessRuleException("partIds must all be positive numbers.");
        }
        List<Part> parts = new ArrayList<>();
        for (Long partId : partIds) {
            parts.add(partRepository.findById(partId)
                    .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + partId)));
        }
        return parts;
    }

    /**
     * Assembles the provider context from real database values only. Absent
     * values are omitted — nothing is invented. Customer PII (name, phone,
     * email, address) is deliberately never included.
     */
    private Map<String, Object> buildContext(RepairCostEstimationRequestDTO request,
                                             Vehicle vehicle,
                                             JobCard jobCard,
                                             List<Part> parts) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("serviceDescription", request.getServiceDescription().trim());
        ctx.put("currency", DEFAULT_CURRENCY);

        if (request.getLabourHours() != null) {
            ctx.put("labourHours", request.getLabourHours());
        }
        if (isNotBlank(request.getInspectionFindings())) {
            ctx.put("inspectionFindings", request.getInspectionFindings().trim());
        }
        if (isNotBlank(request.getTechnicianNotes())) {
            ctx.put("technicianNotes", request.getTechnicianNotes().trim());
        }

        // Vehicle context — only when a vehicleId was supplied
        if (vehicle != null) {
            putIfNotNull(ctx, "vehicleMake",     vehicle.getMake());
            putIfNotNull(ctx, "vehicleModel",    vehicle.getModel());
            putIfNotNull(ctx, "vehicleVariant",  vehicle.getVariant());
            putIfNotNull(ctx, "vehicleYear",     vehicle.getYear());
            putIfNotNull(ctx, "vehicleMileage",  vehicle.getMileage());
            putIfNotNull(ctx, "registrationNo",  vehicle.getRegistrationNo());
        }

        // Job card context — only when a jobCardId was supplied
        if (jobCard != null) {
            putIfNotNull(ctx, "jobCardNumber",  jobCard.getJobCardNumber());
            putIfNotNull(ctx, "serviceType",    jobCard.getServiceType());
            putIfNotNull(ctx, "complaint",      jobCard.getComplaint());
            putIfNotNull(ctx, "technicianNotes", jobCard.getTechnicianNotes());
            putIfNotNull(ctx, "odometerReading", jobCard.getOdometerReading());
        }

        // Parts context — real pricing and live stock availability
        if (!parts.isEmpty()) {
            List<Map<String, Object>> partContext = new ArrayList<>();
            for (Part p : parts) {
                Map<String, Object> entry = new LinkedHashMap<>();
                putIfNotNull(entry, "sku",          p.getSku());
                putIfNotNull(entry, "name",         p.getName());
                putIfNotNull(entry, "unit",         p.getUnit());
                putIfNotNull(entry, "sellingPrice", p.getSellingPrice());
                putIfNotNull(entry, "stockQty",     p.getStockQty());

                // Out-of-stock is material to both cost and availability.
                if (p.getStockQty() != null && p.getStockQty() <= 0) {
                    entry.put("inStock", false);
                } else if (p.getStockQty() != null && p.getMinStock() != null
                        && p.getStockQty() <= p.getMinStock()) {
                    entry.put("inStock", true);
                    entry.put("lowStock", true);
                } else {
                    entry.put("inStock", true);
                }
                partContext.add(entry);
            }
            ctx.put("parts", partContext);
        }

        // Ask for a structured breakdown rather than prose.
        ctx.put("requestedOutput",
                "Return estimatedTotalCost, a costBreakdown array of {category, description, amount} "
              + "with category in LABOUR|PARTS|OTHER, and a confidence value between 0 and 1.");

        return ctx;
    }

    /**
     * Provider availability, using the same signal convention as
     * VehicleDiagnosisServiceImpl: a zero confidence combined with a
     * human-confirmation flag is how {@code NoopAiProviderClient} and a failed
     * provider signal unavailability. A null confidence also means the provider
     * could not score its own output.
     */
    private boolean isProviderUnavailable(AiResult result) {
        if (result == null || result.getConfidence() == null) {
            return true;
        }
        return result.getConfidence().compareTo(BigDecimal.ZERO) == 0
                && result.isRequiresHumanConfirmation();
    }

    private RepairCostEstimationResponseDTO buildUnavailableResponse(
            RepairCostEstimationRequestDTO request, List<String> limitations) {
        return RepairCostEstimationResponseDTO.builder()
                .vehicleId(request.getVehicleId())
                .jobCardId(request.getJobCardId())
                .estimatedTotalCost(null)        // never fabricate a figure
                .currency(DEFAULT_CURRENCY)
                .costBreakdown(List.of())
                .partsCost(null)
                .labourCost(null)
                .confidence(null)
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(limitations)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private RepairCostEstimationResponseDTO mapToResponse(RepairCostEstimationRequestDTO request,
                                                          AiResult result,
                                                          List<String> limitations) {
        List<RepairCostEstimationResponseDTO.CostItemDTO> breakdown =
                extractBreakdown(result.getDetails());

        BigDecimal total = resolveTotal(result, breakdown, limitations);
        BigDecimal confidence = result.getConfidence();

        // Flag low confidence explicitly so the UI can escalate to a manager.
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            limitations.add("The AI provider returned low confidence for this estimate. "
                    + "A manual review is strongly recommended before the figure is used.");
        }

        return RepairCostEstimationResponseDTO.builder()
                .vehicleId(request.getVehicleId())
                .jobCardId(request.getJobCardId())
                .estimatedTotalCost(total)
                .currency(DEFAULT_CURRENCY)
                .costBreakdown(breakdown)
                .partsCost(sumCategory(breakdown, "PARTS"))
                .labourCost(sumCategory(breakdown, "LABOUR"))
                .confidence(confidence)
                .humanReviewRequired(true)   // always true — SRS BR-09 / FR-AI-08
                .providerUnavailable(false)
                .dataLimitations(limitations)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Prefers an explicit total from the provider, otherwise falls back to the
     * sum of the breakdown. Returns null rather than guessing.
     */
    private BigDecimal resolveTotal(AiResult result,
                                    List<RepairCostEstimationResponseDTO.CostItemDTO> breakdown,
                                    List<String> limitations) {
        BigDecimal total = extractDecimal(result.getDetails(), "estimatedTotalCost");
        if (total != null) {
            return money(total);
        }
        if (!breakdown.isEmpty()) {
            return money(breakdown.stream()
                    .map(RepairCostEstimationResponseDTO.CostItemDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        limitations.add("The AI provider did not return a cost figure or a cost breakdown. "
                + "No estimate could be produced.");
        return null;
    }

    private List<RepairCostEstimationResponseDTO.CostItemDTO> extractBreakdown(
            Map<String, Object> details) {
        List<RepairCostEstimationResponseDTO.CostItemDTO> items = new ArrayList<>();
        if (details == null) {
            return items;
        }
        Object raw = details.get("costBreakdown");
        if (!(raw instanceof List<?> rawList)) {
            return items;
        }
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            BigDecimal amount = toBigDecimal(map.get("amount"));
            if (amount == null) {
                continue;   // skip malformed lines rather than emitting noise
            }
            Object description = map.get("description");
            Object category     = map.get("category");
            items.add(new RepairCostEstimationResponseDTO.CostItemDTO(
                    normaliseCategory(category != null ? String.valueOf(category) : null),
                    description != null ? String.valueOf(description) : null,
                    money(amount)));
        }
        return items;
    }

    private String normaliseCategory(String category) {
        if (category == null) {
            return "OTHER";
        }
        return switch (category.trim().toUpperCase()) {
            case "LABOUR", "LABOR" -> "LABOUR";
            case "PARTS", "PART"   -> "PARTS";
            default                -> "OTHER";
        };
    }

    private BigDecimal sumCategory(List<RepairCostEstimationResponseDTO.CostItemDTO> items,
                                   String category) {
        boolean present = items.stream().anyMatch(i -> category.equals(i.getCategory()));
        if (!present) {
            return null;
        }
        return money(items.stream()
                .filter(i -> category.equals(i.getCategory()))
                .map(RepairCostEstimationResponseDTO.CostItemDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal extractDecimal(Map<String, Object> details, String key) {
        return details == null ? null : toBigDecimal(details.get(key));
    }

    /** Lenient numeric coercion; returns null for anything unparseable. */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
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
