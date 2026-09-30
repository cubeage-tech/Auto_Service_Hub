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
 * AI Repair Cost Estimation response (SRS Section 5.2, FR-AI-05..08).
 *
 * <p><strong>This is an estimate, not a quote or a guaranteed price.</strong>
 * The estimate is advisory: a service advisor or manager must review it, adjust
 * it against a proper estimate document, and confirm it with the customer before
 * any work is authorised. Per SRS BR-09 and FR-AI-04 the response always carries
 * {@code humanReviewRequired = true} and a plain-language {@code disclaimer}.
 *
 * <p>All monetary values are nullable by design. When the AI provider is
 * unavailable, or when the estimate cannot be grounded, no fabricated figure is
 * returned — the corresponding fields are {@code null} and the reason is
 * reported in {@code dataLimitations}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    description = "AI-generated repair cost estimate. Advisory only — never a guaranteed price. " +
                  "Requires human review before being quoted to a customer (SRS BR-09)."
)
public class RepairCostEstimationResponseDTO {

    @Schema(description = "Vehicle database ID used to ground the estimate (null if not supplied).",
            example = "3")
    private Long vehicleId;

    @Schema(description = "Job card database ID used to ground the estimate (null if not supplied).",
            example = "7")
    private Long jobCardId;

    @Schema(
        description = "AI-estimated total repair cost. NULL when the provider is unavailable or the " +
                      "estimate could not be produced — a figure is never invented.",
        example = "8450.00"
    )
    private BigDecimal estimatedTotalCost;

    @Schema(
        description = "ISO 4217 currency code for every monetary value in this response.",
        example = "INR"
    )
    private String currency;

    @Schema(
        description = "Itemised cost breakdown when the provider supplies one. Empty when unavailable. " +
                      "These are advisory lines, not billable items — nothing here is persisted to an " +
                      "estimate, job card or invoice.",
        example = "[{\"category\":\"LABOUR\",\"description\":\"Brake disc and pad replacement\",\"amount\":3200.00}]"
    )
    private List<CostItemDTO> costBreakdown;

    @Schema(description = "Sum of PART category lines from the breakdown, when available.",
            example = "5250.00")
    private BigDecimal partsCost;

    @Schema(description = "Sum of LABOUR category lines from the breakdown, when available.",
            example = "3200.00")
    private BigDecimal labourCost;

    @Schema(
        description = "Provider confidence in the estimate, 0.00–1.00. Null or 0.00 when the provider is " +
                      "unavailable. A low value increases the likelihood that human review is called out " +
                      "in dataLimitations.",
        example = "0.74"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Always true (SRS BR-09 / FR-AI-04). An AI estimate must never be finalised or " +
                      "quoted to a customer without human review.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "True when the AI provider was unavailable or no real provider is configured. " +
                      "When true, estimatedTotalCost is null and no figure should be quoted.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(
        description = "Concrete statements of what data was missing or unavailable while producing this " +
                      "estimate. Non-empty whenever the estimate could not be fully grounded. " +
                      "Supports 'what would improve this estimate' reasoning without inventing data.",
        example = "[\"No vehicle data supplied — make and model were not considered.\", " +
                  "\"No parts were specified — the parts component is not grounded in inventory pricing.\"]"
    )
    private List<String> dataLimitations;

    @Schema(
        description = "Plain-language statement that this is an estimate, not a quote.",
        example = "This cost estimate is AI-generated and is an approximation only, not a quotation or a " +
                  "guaranteed price. It must be reviewed and confirmed by a service advisor or manager " +
                  "and agreed with the customer before any work is authorised."
    )
    private String disclaimer;

    @Schema(description = "Timestamp when the estimate was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;

    /**
     * A single advisory line of an estimated cost breakdown.
     *
     * <p>These are display-only. They are never written to an invoice, estimate
     * or job card by this service.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One advisory line of the estimated cost breakdown.")
    public static class CostItemDTO {

        @Schema(description = "Cost category for this line.", example = "LABOUR", allowableValues = {"LABOUR", "PARTS", "OTHER"})
        private String category;

        @Schema(description = "Description of the estimated line.", example = "Front brake disc and pad replacement")
        private String description;

        @Schema(description = "Estimated amount for this line in the response currency.", example = "3200.00")
        private BigDecimal amount;
    }
}
