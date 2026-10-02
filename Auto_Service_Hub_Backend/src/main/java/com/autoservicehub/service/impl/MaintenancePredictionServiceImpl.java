package com.autoservicehub.service.impl;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.MaintenancePredictionRequestDTO;
import com.autoservicehub.dto.MaintenancePredictionResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.MaintenancePredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI Maintenance Prediction implementation (SRS Section 5.3, FR-AI-09..12).
 *
 * <p><strong>SRS BR-09 compliance:</strong> this service never writes a job
 * card, task, estimate, invoice or notification. Predictions are advisory
 * display output only; a qualified professional must review them before
 * anything is recommended to a customer.
 *
 * <p><strong>Nothing is fabricated.</strong> Only data that actually exists in
 * the database is passed to the provider, and only data the provider actually
 * returns is reflected in the response. A missing due date, mileage or item
 * stays {@code null}; the gap is explained in {@code dataLimitations}.
 *
 * <p><strong>Known data-model gaps</strong> are surfaced, not worked around:
 * {@code Inspection} has no vehicle or job-card link, {@code JobTask} has no
 * parent link, and there is no maintenance-schedule or service-interval table.
 * None of these are simulated.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenancePredictionServiceImpl implements MaintenancePredictionService {

    /** Below this confidence the prediction is flagged for closer manual review. */
    static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.50");

    /**
     * Number of completed services below which interval-based prediction is
     * considered unreliable. A brand-new or rarely-serviced vehicle genuinely
     * has too little history, and saying so is better than guessing.
     */
    static final int MIN_RELIABLE_HISTORY = 2;

    /** Cap on how many history entries are forwarded, to keep the payload small. */
    static final int MAX_HISTORY_ENTRIES = 10;

    public static final String DISCLAIMER =
            "This maintenance prediction is AI-generated and is advisory only. It is NOT a service " +
          "schedule and NOT a safety instruction. It must be reviewed and confirmed by a qualified " +
          "technician before any work is recommended to a customer. Predictions can be wrong, " +
          "particularly where service history is incomplete. " +
          "SmartGarage AI accepts no liability for maintenance decisions based solely on this output.";

    private final AiOrchestrationService  aiOrchestrationService;
    private final VehicleRepository       vehicleRepository;
    private final JobCardRepository       jobCardRepository;
    private final AppointmentRepository   appointmentRepository;

    @Override
    public MaintenancePredictionResponseDTO predict(MaintenancePredictionRequestDTO request) {

        // ── 1. Validation ─────────────────────────────────────────────────
        // @NotNull/@Positive on vehicleId are enforced by Bean Validation.
        Long vehicleId = request.getVehicleId();
        if (vehicleId == null) {
            throw new ResourceNotFoundException(
                    "vehicleId is required to predict maintenance for a vehicle.");
        }

        // ── 2. Resolve the vehicle ────────────────────────────────────────
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehicleId));

        // ── 3. Gather real service history ────────────────────────────────
        List<String> limitations = new ArrayList<>();

        List<JobCard> jobCards = jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(vehicleId);
        List<Appointment> appointments =
                appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(vehicleId);

        MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO historySummary =
                summariseHistory(jobCards, limitations);

        if (vehicle.getMileage() == null) {
            limitations.add("No current odometer reading is recorded for this vehicle, so mileage-based " +
                    "maintenance intervals cannot be evaluated.");
        } else if (request.getCurrentMileage() != null
                && !request.getCurrentMileage().equals(vehicle.getMileage())) {
            limitations.add("An advisor-supplied odometer reading (" + request.getCurrentMileage() +
                    ") differs from the stored reading (" + vehicle.getMileage() +
                    "). The stored value is used as authoritative.");
        }

        // Report the structural gaps that limit this prediction.
        limitations.add("The system has no maintenance schedule or service-interval table, so " +
                "interval-based predictions are advisory only.");
        limitations.add("Inspection records are not linked to a vehicle or job card in the current data " +
                "model, so inspection findings could not be included.");

        // ── 4. Build the provider request ────────────────────────────────
        Map<String, Object> context =
                buildContext(request, vehicle, jobCards, appointments, historySummary);

        AiRequest aiRequest = new AiRequest();
        aiRequest.setFeatureType(AiFeatureType.MAINTENANCE_PREDICTION);
        aiRequest.setVehicleId(vehicleId);
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
            limitations.add("The AI provider is unavailable, so no maintenance prediction could be " +
                    "produced. Please perform a manual inspection.");
            return buildUnavailableResponse(vehicleId, historySummary, limitations);
        }

        // ── 6. Map provider output to the typed response ─────────────────
        return mapToResponse(vehicleId, result, historySummary, limitations);
    }

    /**
     * Builds a factual summary of the vehicle's recorded history. Reports real
     * counts and the most recent completed service only — never an estimate.
     */
    private MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO summariseHistory(
            List<JobCard> jobCards, List<String> limitations) {

        int total = jobCards == null ? 0 : jobCards.size();
        int completed = 0;
        LocalDateTime lastServicedAt = null;
        Integer lastOdometer = null;

        if (jobCards != null) {
            for (JobCard jc : jobCards) {
                if ("DELIVERED".equalsIgnoreCase(jc.getStatus())) {
                    completed++;
                }
            }
            // Job cards arrive newest-first, so the first completed one is the latest.
            for (JobCard jc : jobCards) {
                if ("DELIVERED".equalsIgnoreCase(jc.getStatus()) && jc.getCompletedDate() != null) {
                    lastServicedAt = jc.getCompletedDate();
                    lastOdometer = jc.getOdometerReading();
                    break;
                }
            }
        }

        if (completed == 0) {
            limitations.add("No completed service is recorded for this vehicle, so there is no service " +
                    "history to base interval predictions on.");
        } else if (completed < MIN_RELIABLE_HISTORY) {
            limitations.add("Only " + completed + " completed service is recorded for this vehicle, " +
                    "so interval-based prediction is unreliable.");
        }
        if (lastOdometer == null) {
            limitations.add("No odometer reading is recorded on the most recent service, so usage " +
                    "since the last service cannot be determined.");
        }

        return new MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO(
                total, completed, lastServicedAt, lastOdometer);
    }

    /**
     * Assembles the provider context from real database values only. Customer
     * PII (name, phone, email, address) is deliberately never included.
     */
    private Map<String, Object> buildContext(MaintenancePredictionRequestDTO request,
                                             Vehicle vehicle,
                                             List<JobCard> jobCards,
                                             List<Appointment> appointments,
                                             MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO summary) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // Vehicle facts
        putIfNotNull(ctx, "vehicleMake",      vehicle.getMake());
        putIfNotNull(ctx, "vehicleModel",     vehicle.getModel());
        putIfNotNull(ctx, "vehicleVariant",   vehicle.getVariant());
        putIfNotNull(ctx, "vehicleYear",      vehicle.getYear());
        putIfNotNull(ctx, "currentMileage",   vehicle.getMileage());
        putIfNotNull(ctx, "warrantyExpiry",  vehicle.getWarrantyExpiry());
        putIfNotNull(ctx, "insuranceExpiry", vehicle.getInsuranceExpiry());

        if (request.getCurrentMileage() != null) {
            ctx.put("advisorReportedMileage", request.getCurrentMileage());
        }
        if (isNotBlank(request.getNotes())) {
            ctx.put("advisorNotes", request.getNotes().trim());
        }

        // Factual history summary
        ctx.put("totalJobCards",      summary.getTotalJobCards());
        ctx.put("completedJobCards",  summary.getCompletedJobCards());
        putIfNotNull(ctx, "lastServicedAt",       summary.getLastServicedAt());
        putIfNotNull(ctx, "lastRecordedOdometer", summary.getLastRecordedOdometer());

        // Recent completed services — real rows only, capped for payload size
        List<Map<String, Object>> history = new ArrayList<>();
        if (jobCards != null) {
            jobCards.stream()
                    .filter(jc -> "DELIVERED".equalsIgnoreCase(jc.getStatus()))
                    .limit(MAX_HISTORY_ENTRIES)
                    .forEach(jc -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        putIfNotNull(entry, "serviceType",     jc.getServiceType());
                        putIfNotNull(entry, "completedDate",   jc.getCompletedDate());
                        putIfNotNull(entry, "odometerReading", jc.getOdometerReading());
                        putIfNotNull(entry, "technicianNotes", jc.getTechnicianNotes());
                        history.add(entry);
                    });
        }
        ctx.put("recentCompletedServices", history);

        // Booking history — real appointments for this vehicle
        if (appointments != null && !appointments.isEmpty()) {
            ctx.put("appointmentCount", appointments.size());
            List<Map<String, Object>> recent = new ArrayList<>();
            appointments.stream().limit(MAX_HISTORY_ENTRIES).forEach(a -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                putIfNotNull(entry, "serviceType",    a.getServiceType());
                putIfNotNull(entry, "appointmentAt",  a.getAppointmentAt());
                putIfNotNull(entry, "status",         a.getStatus());
                recent.add(entry);
            });
            ctx.put("recentAppointments", recent);
        }

        // Ask for a structured prediction rather than prose.
        ctx.put("requestedOutput",
                "Return a summary, a predictedItems array of {item, priority, dueByMileage, dueByDate, " +
              "rationale} where priority is one of LOW|MEDIUM|HIGH|URGENT, and a confidence value " +
              "between 0 and 1. Omit any field you cannot support rather than guessing it.");

        return ctx;
    }

    /**
     * Provider availability, using the same signal convention as the diagnosis
     * and repair-cost services: zero confidence combined with a
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

    private MaintenancePredictionResponseDTO buildUnavailableResponse(
            Long vehicleId,
            MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO historySummary,
            List<String> limitations) {
        return MaintenancePredictionResponseDTO.builder()
                .vehicleId(vehicleId)
                .summary(null)                 // never fabricate a prediction
                .predictedItems(List.of())     // no items invented
                .confidence(null)
                .humanReviewRequired(true)
                .providerUnavailable(true)
                .dataLimitations(limitations)
                .serviceHistorySummary(historySummary)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    private MaintenancePredictionResponseDTO mapToResponse(
            Long vehicleId,
            AiResult result,
            MaintenancePredictionResponseDTO.ServiceHistorySummaryDTO historySummary,
            List<String> limitations) {

        List<MaintenancePredictionResponseDTO.MaintenanceItemDTO> items =
                extractItems(result.getDetails());

        BigDecimal confidence = result.getConfidence();
        if (confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            limitations.add("The AI provider returned low confidence for this prediction. A manual " +
                    "inspection is strongly recommended before any maintenance is discussed with the " +
                    "customer.");
        }
        if (items.isEmpty()) {
            limitations.add("The AI provider returned no predicted maintenance items.");
        }

        // Summary falls back to the provider's own summary text; never invented.
        String summary = result.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = null;
        }

        return MaintenancePredictionResponseDTO.builder()
                .vehicleId(vehicleId)
                .summary(summary)
                .predictedItems(items)
                .confidence(confidence)
                .humanReviewRequired(true)   // always true — SRS BR-09 / FR-AI-12
                .providerUnavailable(false)
                .dataLimitations(limitations)
                .serviceHistorySummary(historySummary)
                .disclaimer(DISCLAIMER)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Parses the provider's predicted items. Fields the provider did not supply
     * stay null — a due date is never defaulted to today and a priority is
     * never invented. Entries without an item name are skipped.
     */
    private List<MaintenancePredictionResponseDTO.MaintenanceItemDTO> extractItems(
            Map<String, Object> details) {
        List<MaintenancePredictionResponseDTO.MaintenanceItemDTO> items = new ArrayList<>();
        if (details == null) {
            return items;
        }
        Object raw = details.get("predictedItems");
        if (!(raw instanceof List<?> rawList)) {
            return items;
        }
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object name = map.get("item");
            if (name == null || String.valueOf(name).isBlank()) {
                continue;
            }
            Object priority   = map.get("priority");
            Object rationale  = map.get("rationale");
            items.add(new MaintenancePredictionResponseDTO.MaintenanceItemDTO(
                    String.valueOf(name).trim(),
                    priority != null ? normalisePriority(String.valueOf(priority)) : null,
                    toInteger(map.get("dueByMileage")),
                    toDateTime(map.get("dueByDate")),
                    rationale != null ? String.valueOf(rationale) : null));
        }
        return items;
    }

    private String normalisePriority(String priority) {
        return switch (priority.trim().toUpperCase()) {
            case "LOW", "MINOR"    -> "LOW";
            case "MEDIUM", "NORMAL" -> "MEDIUM";
            case "HIGH", "MAJOR"    -> "HIGH";
            case "URGENT", "CRITICAL" -> "URGENT";
            default                 -> priority.trim().toUpperCase();
        };
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

    /** Accepts an ISO-8601 date/time string. Returns null rather than guessing. */
    private LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ex) {
            return null;   // unparseable date — not fabricated
        }
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
