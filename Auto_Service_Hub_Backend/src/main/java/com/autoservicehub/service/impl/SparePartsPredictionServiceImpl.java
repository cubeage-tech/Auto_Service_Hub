package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.SparePartsPredictionRequestDTO;
import com.autoservicehub.dto.SparePartsPredictionResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.SparePartsPredictionService;
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
 * AI Spare Parts Prediction implementation (SRS Section 5.5, FR-AI-21..24).
 *
 * <p><strong>SRS BR-09 compliance:</strong> this service performs NO inventory
 * action. Stock is neither deducted nor reserved, {@code Part.stockQty} is never
 * written, and no stock movement, purchase or supplier record is created. The
 * response is advisory output that a parts professional must confirm before
 * anything is ordered.
 *
 * <p><strong>Nothing is fabricated.</strong> Only catalogue data that actually
 * exists is offered to the provider, and only parts that can be matched to a real
 * catalogue record are returned. A provider-supplied part id that does not exist
 * is discarded rather than passed through or replaced with a "close enough" part.
 * Quantities the provider does not supply stay {@code null} — never defaulted to
 * zero or one.
 *
 * <p><strong>Known data-model gaps are surfaced, not worked around:</strong>
 * {@code JobCard} has no link to parts, {@code StockMovement} and
 * {@code PurchaseItem} have no part or purchase link respectively, and
 * {@code Part} carries no category, supplier or vehicle-compatibility data. There
 * is therefore no historical part-usage signal to learn from, and no way to know
 * which parts fit which vehicle. These are reported in {@code dataLimitations}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SparePartsPredictionServiceImpl implements SparePartsPredictionService {

    /** Below this confidence the prediction is flagged for closer manual review. */
    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.50");

    /**
     * Cap on how many catalogue parts are sent to the provider, to keep the
     * outbound payload bounded. The total offered is always reported so the
     * reviewer can see that the list was truncated.
     */
    static final int MAX_CATALOGUE_ENTRIES = 200;

    public static final String DISCLAIMER =
            "This spare parts prediction is AI-generated and is advisory only. No stock has been reserved, " +
          "deducted or ordered, and no stock movement has been recorded. A parts professional must review " +
          "this prediction and confirm the actual requirement before ordering. The system does not record " +
          "which parts were used on past jobs, so this prediction is not based on this workshop's own " +
          "historical parts usage. Predictions can be wrong; quantities are guidance, not a confirmed " +
          "billable requirement. SmartGarage AI accepts no liability for parts ordered on the basis of " +
          "this output.";

    private final AiOrchestrationService aiOrchestrationService;
    private final PartRepository         partRepository;
    private final JobCardRepository      jobCardRepository;
    private final VehicleRepository      vehicleRepository;

    @Override
    public SparePartsPredictionResponseDTO predict(SparePartsPredictionRequestDTO request) {

        // ── 1. Validation ─────────────────────────────────────────────────
        // @NotBlank on serviceDescription is enforced by Bean Validation.
        String serviceDescription = request.getServiceDescription() == null
                ? "" : request.getServiceDescription().trim();
        if (serviceDescription.length() < 5) {
            throw new BusinessRuleException(
                    "serviceDescription is too short to predict parts for. Please describe the repair " +
                  "work in at least 5 characters.");
        }

        // ── 2. Optional context, resolved from real records ───────────────
        List<String> limitations = describeDataModelGaps();

        JobCard jobCard = resolveJobCard(request.getJobCardId(), limitations);
        Vehicle vehicle = resolveVehicle(request.getVehicleId(), jobCard, limitations);

        // ── 3. Build the real candidate catalogue ────────────────────────
        List<Part> catalogue = resolveCatalogue(request, limitations);
        SparePartsPredictionResponseDTO.InventorySnapshotDTO snapshot =
                buildSnapshot(catalogue);

        if (catalogue.isEmpty()) {
            limitations.add("No parts exist in the catalogue, so no parts could be predicted. Please " +
                    "add parts to inventory first.");
            return buildNoPredictionResponse(request, snapshot, null, limitations);
        }

        // ── 4. Build the provider request ────────────────────────────────
        Map<String, Object> context = buildContext(request, jobCard, vehicle, catalogue, limitations);

        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.PARTS_PREDICTION);
        aiRequest.setVehicleId(request.getVehicleId());
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
            limitations.add("The AI provider is unavailable, so no parts could be predicted. Please " +
                    "determine the required parts manually.");
            return buildNoPredictionResponse(request, snapshot, null, limitations);
        }

        // ── 6. Map and VALIDATE the provider's parts ─────────────────────
        return mapToResponse(request, result, catalogue, snapshot, limitations);
    }

    /**
     * The data a parts prediction would ideally use but which the current model
     * does not hold. Stated plainly rather than approximated.
     */
    private List<String> describeDataModelGaps() {
        List<String> gaps = new ArrayList<>();
        gaps.add("Parts are not linked to job cards, so this workshop's historical parts usage could not " +
                 "be analysed and no usage frequency or consumption pattern is available.");
        gaps.add("Stock movements are not linked to parts, so no stock issue or receipt history could be " +
                 "considered.");
        gaps.add("Parts carry no category, supplier or vehicle-compatibility data, so suitability for this " +
                 "specific vehicle could not be verified.");
        return gaps;
    }

    private JobCard resolveJobCard(Long jobCardId, List<String> limitations) {
        if (jobCardId == null) {
            return null;
        }
        return jobCardRepository.findById(jobCardId)
                .orElseThrow(() -> new ResourceNotFoundException("JobCard not found: " + jobCardId));
    }

    /**
     * Resolves the vehicle. When only a job card is supplied, the vehicle is taken
     * from that job card's existing relationship.
     */
    private Vehicle resolveVehicle(Long vehicleId, JobCard jobCard, List<String> limitations) {
        if (vehicleId != null) {
            return vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehicleId));
        }
        if (jobCard != null && jobCard.getVehicle() != null) {
            return jobCard.getVehicle();
        }
        return null;
    }

    /**
     * Resolves the candidate catalogue. When the caller narrows it, every
     * requested id must resolve to a real part — anything else is a 404 rather
     * than being silently dropped or substituted.
     */
    private List<Part> resolveCatalogue(SparePartsPredictionRequestDTO request,
                                       List<String> limitations) {
        List<Long> requested = request.getPartIds();
        if (requested != null && !requested.isEmpty()) {
            // NB: do not use requested.contains(null) — immutable lists throw NPE.
            if (requested.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new BusinessRuleException("partIds must all be positive numbers.");
            }
            List<Part> parts = new ArrayList<>();
            for (Long id : requested) {
                parts.add(partRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Part not found: " + id)));
            }
            return parts;
        }
        List<Part> all = partRepository.findAll();
        if (all.size() > MAX_CATALOGUE_ENTRIES) {
            limitations.add("The parts catalogue has " + all.size() + " entries; only the first " +
                    MAX_CATALOGUE_ENTRIES + " were offered to the AI provider, so a relevant part may " +
                    "have been outside the list it could choose from.");
            return new ArrayList<>(all.subList(0, MAX_CATALOGUE_ENTRIES));
        }
        return all;
    }

    /** Real counts from real part rows — never an estimate of demand. */
    private SparePartsPredictionResponseDTO.InventorySnapshotDTO buildSnapshot(List<Part> catalogue) {
        int outOfStock = 0;
        int lowStock = 0;
        for (Part p : catalogue) {
            Integer qty = p.getStockQty();
            if (qty == null) {
                continue;   // unknown stock is not counted as zero
            }
            if (qty <= 0) {
                outOfStock++;
            } else if (p.getMinStock() != null && qty <= p.getMinStock()) {
                lowStock++;
            }
        }
        return new SparePartsPredictionResponseDTO.InventorySnapshotDTO(
                catalogue.size(), outOfStock, lowStock);
    }

    /**
     * Assembles the provider context from real database values only. Customer PII
     * (name, phone, email, address) is deliberately never included.
     */
    private Map<String, Object> buildContext(SparePartsPredictionRequestDTO request,
                                             JobCard jobCard,
                                             Vehicle vehicle,
                                             List<Part> catalogue,
                                             List<String> limitations) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("serviceDescription", request.getServiceDescription().trim());

        if (isNotBlank(request.getAdditionalNotes())) {
            ctx.put("additionalNotes", request.getAdditionalNotes().trim());
        }

        if (jobCard != null) {
            putIfNotNull(ctx, "jobCardNumber", jobCard.getJobCardNumber());
            putIfNotNull(ctx, "jobStatus",     jobCard.getStatus());
            putIfNotNull(ctx, "serviceType",   jobCard.getServiceType());
            putIfNotNull(ctx, "complaint",     jobCard.getComplaint());
            putIfNotNull(ctx, "technicianNotes", jobCard.getTechnicianNotes());
        } else {
            limitations.add("No job card was supplied, so the recorded service type and technician notes " +
                    "were not considered.");
        }

        if (vehicle != null) {
            putIfNotNull(ctx, "vehicleMake",    vehicle.getMake());
            putIfNotNull(ctx, "vehicleModel",   vehicle.getModel());
            putIfNotNull(ctx, "vehicleVariant", vehicle.getVariant());
            putIfNotNull(ctx, "vehicleYear",    vehicle.getYear());
        } else {
            limitations.add("No vehicle was supplied, so part compatibility for a specific vehicle " +
                    "could not be taken into account.");
        }

        // Real catalogue entries with real stock and pricing.
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Part p : catalogue) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfNotNull(entry, "partId",        p.getId());
            putIfNotNull(entry, "sku",           p.getSku());
            putIfNotNull(entry, "name",          p.getName());
            putIfNotNull(entry, "unit",          p.getUnit());
            putIfNotNull(entry, "sellingPrice",  p.getSellingPrice());
            putIfNotNull(entry, "stockQty",      p.getStockQty());
            putIfNotNull(entry, "minStock",      p.getMinStock());
            parts.add(entry);
        }
        ctx.put("availableParts", parts);
        ctx.put("availablePartCount", parts.size());

        ctx.put("requestedOutput",
                "Return predictedParts as an array of {partId, partName, predictedQuantity, rationale} " +
              "using ONLY partId values from the availableParts list. Omit predictedQuantity entirely if " +
              "you are not confident of it rather than guessing. Also return a summary and a confidence " +
              "between 0 and 1. Note that no historical parts usage data is available.");

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

    private SparePartsPredictionResponseDTO buildNoPredictionResponse(
            SparePartsPredictionRequestDTO request,
            SparePartsPredictionResponseDTO.InventorySnapshotDTO snapshot,
            BigDecimal confidence,
            List<String> limitations) {
        return SparePartsPredictionResponseDTO.builder()
                .jobCardId(request.getJobCardId())
                .vehicleId(request.getVehicleId())
                .summary(null)                 // never fabricate a prediction
                .predictedParts(List.of())     // no parts invented
                .confidence(confidence)
                .humanReviewRequired(true)
                .inventoryModified(false)      // nothing was reserved or deducted
                .providerUnavailable(confidence == null)
                .dataLimitations(limitations)
                .inventorySnapshot(snapshot)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private SparePartsPredictionResponseDTO mapToResponse(
            SparePartsPredictionRequestDTO request,
            AiResult result,
            List<Part> catalogue,
            SparePartsPredictionResponseDTO.InventorySnapshotDTO snapshot,
            List<String> limitations) {

        BigDecimal confidence = result.getConfidence();
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            limitations.add("The AI provider returned low confidence for this prediction. A manual " +
                    "review is strongly recommended before ordering any parts.");
        }

        List<SparePartsPredictionResponseDTO.PredictedPartDTO> predicted = extractParts(result, catalogue);
        int discarded = countDiscarded(result, catalogue);

        if (predicted.isEmpty()) {
            limitations.add("The AI provider returned no parts that could be matched to the parts " +
                    "catalogue. Please determine the required parts manually.");
        }
        if (discarded > 0) {
            limitations.add(discarded + " part(s) suggested by the AI provider could not be matched to a " +
                    "real catalogue record and were discarded. No substitute part was chosen.");
        }

        return SparePartsPredictionResponseDTO.builder()
                .jobCardId(request.getJobCardId())
                .vehicleId(request.getVehicleId())
                .summary(toText(result.getSummary()))
                .predictedParts(predicted)
                .confidence(confidence)
                .humanReviewRequired(true)      // always true — SRS BR-09 / FR-AI-24
                .inventoryModified(false)       // prediction only — no stock action taken
                .providerUnavailable(false)
                .dataLimitations(limitations)
                .inventorySnapshot(snapshot)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Parses and VALIDATES the provider's parts. A part is only returned when
     * its id matches a real catalogue entry. A hallucinated id is discarded — it
     * is never passed through, and no "closest" part is substituted. A quantity
     * the provider did not supply stays null rather than defaulting to 0 or 1.
     */
    private List<SparePartsPredictionResponseDTO.PredictedPartDTO> extractParts(
            AiResult result, List<Part> catalogue) {
        List<SparePartsPredictionResponseDTO.PredictedPartDTO> items = new ArrayList<>();
        if (result.getDetails() == null) {
            return items;
        }
        Object raw = result.getDetails().get("predictedParts");
        if (!(raw instanceof List<?> rawList)) {
            return items;
        }
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Long partId = toLong(map.get("partId"));
            Optional<Part> match = partId == null ? Optional.empty()
                    : catalogue.stream().filter(p -> partId.equals(p.getId())).findFirst();
            if (match.isEmpty()) {
                continue;   // unknown or invented part — discarded, not substituted
            }
            Part part = match.get();
            String providerName = toText(map.get("partName"));
            items.add(new SparePartsPredictionResponseDTO.PredictedPartDTO(
                    part.getId(),
                    providerName != null ? providerName : part.getName(),
                    part.getSku(),
                    toInteger(map.get("predictedQuantity")),   // null when not supplied
                    part.getUnit(),
                    part.getStockQty(),                       // read-only observation
                    part.getStockQty() == null ? null : part.getStockQty() > 0,
                    toText(map.get("rationale"))));
        }
        return items;
    }

    /** Counts provider-suggested parts that did not match a real catalogue entry. */
    private int countDiscarded(AiResult result, List<Part> catalogue) {
        if (result.getDetails() == null) {
            return 0;
        }
        Object raw = result.getDetails().get("predictedParts");
        if (!(raw instanceof List<?> rawList)) {
            return 0;
        }
        int discarded = 0;
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Long partId = toLong(map.get("partId"));
            boolean matched = partId != null
                    && catalogue.stream().anyMatch(p -> partId.equals(p.getId()));
            if (!matched) {
                discarded++;
            }
        }
        return discarded;
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

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
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
