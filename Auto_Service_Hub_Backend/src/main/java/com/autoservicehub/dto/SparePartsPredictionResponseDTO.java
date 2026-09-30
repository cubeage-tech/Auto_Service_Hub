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
 * AI Spare Parts Prediction response (SRS Section 5.5, FR-AI-21..24).
 *
 * <p><strong>Prediction only. No stock is touched.</strong> This service never
 * deducts, reserves or issues stock, never creates a stock movement, purchase
 * or supplier record, and never modifies a job card. The response describes
 * what the AI believes may be required so a human can decide, order and record
 * it properly through the inventory workflow. Per SRS BR-09 and FR-AI-24 the
 * response always carries {@code humanReviewRequired = true} and a
 * plain-language {@code disclaimer}.
 *
 * <p>All predicted fields are nullable by design. When the provider is
 * unavailable, returns no part, or names a part that does not exist in the
 * catalogue, the corresponding fields stay {@code null} and the reason appears
 * in {@code dataLimitations}. No part id, quantity, stock level or consumption
 * figure is ever fabricated.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    description = "AI-generated spare parts prediction. Advisory only — no stock is reserved, deducted or " +
                  "recorded by this endpoint (SRS BR-09)."
)
public class SparePartsPredictionResponseDTO {

    @Schema(description = "Job card database ID the prediction relates to, when one was supplied.", example = "7")
    private Long jobCardId;

    @Schema(description = "Vehicle database ID the prediction relates to, when one was supplied.", example = "3")
    private Long vehicleId;

    @Schema(
        description = "Short summary of the predicted parts requirement.",
        example = "Front brake replacement typically requires pads and a disc."
    )
    private String summary;

    @Schema(
        description = "Predicted parts. Empty when the provider is unavailable or produced nothing. Each " +
                      "entry is advisory; nothing here is persisted to stock, a purchase or a job card.",
        example = "[{\"partId\":4,\"partName\":\"Front brake pad set\",\"predictedQuantity\":1}]"
    )
    private List<PredictedPartDTO> predictedParts;

    @Schema(
        description = "Provider confidence, 0.00–1.00. Null when the provider is unavailable. A low value " +
                      "triggers an explicit manual-review warning in dataLimitations.",
        example = "0.69"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Always true (SRS BR-09 / FR-AI-24). Parts predictions must be reviewed by a " +
                      "qualified professional and confirmed against a real parts requirement before ordering.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "Always false. Confirms that this endpoint performed NO inventory action: stock was " +
                      "neither deducted nor reserved, and no stock movement, purchase or job card change " +
                      "was created.",
        example = "false"
    )
    private boolean inventoryModified;

    @Schema(
        description = "True when the AI provider was unavailable or no real provider is configured. When true, " +
                      "predictedParts is empty and no parts requirement should be inferred.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(
        description = "What data was missing or could not support the prediction — in particular the absence " +
                      "of any persisted link between job cards and parts, so historical part usage could " +
                      "not be considered. Non-empty whenever the prediction is weak.",
        example = "[\"Parts are not linked to job cards, so historical part usage for this vehicle could not be analysed.\"]"
    )
    private List<String> dataLimitations;

    @Schema(
        description = "Factual snapshot of the parts catalogue that was available when the prediction was made, " +
                      "including how many parts are currently out of stock or at/below reorder level. " +
                      "Real counts only.",
        example = "{\"catalogueSize\":42,\"outOfStockCount\":3,\"lowStockCount\":5}"
    )
    private InventorySnapshotDTO inventorySnapshot;

    @Schema(
        description = "Plain-language statement that no stock was reserved or ordered.",
        example = "This parts prediction is AI-generated and is advisory only. No stock has been reserved, " +
                  "deducted or ordered, and no stock movement has been recorded. A parts professional must " +
                  "review this prediction and confirm the actual requirement before ordering."
    )
    private String disclaimer;

    @Schema(description = "Timestamp when the prediction was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;

    /**
     * One predicted part. Display-only — never written to stock, a purchase, a
     * stock movement or a job card.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One predicted part. Advisory only — no stock action is performed.")
    public static class PredictedPartDTO {

        @Schema(
            description = "Database ID of the predicted part. NULL when the provider named a part that " +
                          "could not be matched to a real catalogue entry, or did not identify a part at all.",
            example = "4"
        )
        private Long partId;

        @Schema(
            description = "Name the provider supplied for the part, used for display. Null when the " +
                          "provider supplied no name.",
            example = "Front brake pad set"
        )
        private String partName;

        @Schema(
            description = "SKU of the matched catalogue part, when the prediction was matched to a real " +
                          "part record. Null when there is no match.",
            example = "BRK-PAD-01"
        )
        private String partSku;

        @Schema(
            description = "Quantity the provider predicted. NULL when the provider gave no quantity — " +
                          "never defaulted to 0 or 1, and never taken from the request.",
            example = "1"
        )
        private Integer predictedQuantity;

        @Schema(
            description = "Unit of issue as recorded on the matched part. Null when there is no match.",
            example = "set"
        )
        private String unit;

        @Schema(
            description = "Current stock quantity of the matched part at the time of prediction, or null " +
                          "when there is no match. This is a read-only observation and is NOT reserved " +
                          "or deducted by this endpoint.",
            example = "12"
        )
        private Integer currentStockQty;

        @Schema(
            description = "Whether the matched part currently has no stock. Null when there is no match. " +
                          "Flagged so the reviewer knows the part must be ordered.",
            example = "false"
        )
        private Boolean inStock;

        @Schema(
            description = "Reason the provider gave for predicting this part.",
            example = "Brake pads are normally replaced with discs."
        )
        private String rationale;
    }

    /**
     * A factual snapshot of the parts catalogue at prediction time. Real counts
     * only — never an estimate of demand or consumption.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Factual snapshot of the available parts catalogue.")
    public static class InventorySnapshotDTO {

        @Schema(description = "How many catalogue parts were offered to the provider.", example = "42")
        private int catalogueSize;

        @Schema(description = "How many of those currently have zero stock.", example = "3")
        private int outOfStockCount;

        @Schema(description = "How many have stock at or below their recorded minimum.", example = "5")
        private int lowStockCount;
    }
}
