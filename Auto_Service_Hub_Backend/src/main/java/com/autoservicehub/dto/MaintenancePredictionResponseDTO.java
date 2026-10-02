package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI Maintenance Prediction response (SRS Section 5.3, FR-AI-09..12).
 *
 * <p><strong>Predictive, not prescriptive.</strong> These are suggestions to help
 * a service advisor decide what to inspect and discuss with the customer. They
 * are not a maintenance schedule, not a safety instruction, and must never be
 * presented to a customer as a definite requirement. Per SRS BR-09 and
 * FR-AI-12 the response always carries {@code humanReviewRequired = true} and a
 * plain-language {@code disclaimer}.
 *
 * <p>All prediction fields are nullable by design. When the provider is
 * unavailable, or when it declines to predict a due date or mileage, the field
 * is {@code null} and the omission is explained in {@code dataLimitations}. No
 * date, mileage or item is ever fabricated.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    description = "AI-generated maintenance prediction. Advisory only — must be reviewed by a qualified " +
                  "professional before being discussed with a customer (SRS BR-09)."
)
public class MaintenancePredictionResponseDTO {

    @Schema(description = "Vehicle database ID the prediction relates to.", example = "3")
    private Long vehicleId;

    @Schema(
        description = "Short summary of the overall maintenance outlook for this vehicle.",
        example = "Routine service due soon; brake fluid approaching its service interval."
    )
    private String summary;

    @Schema(
        description = "Predicted maintenance items. Empty when the provider is unavailable or produced no " +
                      "items. Each item is advisory and is not persisted anywhere.",
        example = "[{\"item\":\"Engine oil change\",\"priority\":\"MEDIUM\",\"dueByMileage\":55000}]"
    )
    private List<MaintenanceItemDTO> predictedItems;

    @Schema(
        description = "Overall provider confidence, 0.00–1.00. Null when the provider is unavailable. " +
                      "A low or unknown value triggers an explicit manual-review warning in dataLimitations.",
        example = "0.78"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Always true (SRS BR-09 / FR-AI-12). AI maintenance predictions must be reviewed by a " +
                      "qualified professional before being acted on or shown to a customer.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "True when the AI provider was unavailable or no real provider is configured. When true, " +
                      "predictedItems is empty and no prediction should be relied upon.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(
        description = "What data was missing, stale or insufficient to produce a reliable prediction. " +
                      "Non-empty whenever confidence is low or the service history is thin. " +
                      "Supports explaining why a prediction is weak without inventing data.",
        example = "[\"Only 1 completed service is recorded, so interval-based prediction is unreliable.\"]"
    )
    private List<String> dataLimitations;

    @Schema(
        description = "Factual counts of the service history actually found in the database, plus the last " +
                      "serviced date and odometer reading. Null when the vehicle was not found.",
        example = "{\"totalJobCards\":5,\"completedJobCards\":4,\"lastServicedAt\":\"2025-11-02T10:00:00\"}"
    )
    private ServiceHistorySummaryDTO serviceHistorySummary;

    @Schema(
        description = "Plain-language statement that this is an advisory prediction, not a service schedule.",
        example = "This maintenance prediction is AI-generated and is advisory only. It is not a service " +
                  "schedule and not a safety instruction. It must be reviewed and confirmed by a qualified " +
                  "technician before any work is recommended to a customer."
    )
    private String disclaimer;

    @Schema(description = "Timestamp when the prediction was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;

    /**
     * A single predicted maintenance item. Display-only — never written to a
     * job card, estimate or invoice by this service.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One predicted maintenance item. Advisory only.")
    public static class MaintenanceItemDTO {

        @Schema(description = "Name of the predicted maintenance item.",
                example = "Engine oil and filter change")
        private String item;

        @Schema(description = "Priority or severity as reported by the provider. Null when the provider " +
                              "does not express one — never defaulted to a value the provider did not supply.",
                example = "MEDIUM", allowableValues = {"LOW", "MEDIUM", "HIGH", "URGENT"})
        private String priority;

        @Schema(description = "Predicted odometer reading (km) at which this item becomes due. " +
                              "Null when the provider did not predict one.",
                example = "55000")
        private Integer dueByMileage;

        @Schema(description = "Predicted calendar date at which this item becomes due. Null when the " +
                              "provider did not predict one — never defaulted to today.",
                example = "2026-01-15T00:00:00")
        private LocalDateTime dueByDate;

        @Schema(description = "Short rationale explaining why this item is predicted.",
                example = "Last recorded oil change was 12000 km ago.")
        private String rationale;
    }

    /**
     * A factual summary of the service history actually found in the database.
     * Reports real counts only — never estimates.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Factual summary of the vehicle's recorded service history.")
    public static class ServiceHistorySummaryDTO {

        @Schema(description = "Total job cards recorded against this vehicle.", example = "5")
        private int totalJobCards;

        @Schema(description = "How many of those job cards are completed (DELIVERED).", example = "4")
        private int completedJobCards;

        @Schema(description = "Completion date of the most recent completed job card, if any.",
                example = "2025-11-02T10:00:00")
        private LocalDateTime lastServicedAt;

        @Schema(description = "Odometer reading recorded on the most recent job card, if any.", example = "42000")
        private Integer lastRecordedOdometer;
    }
}
