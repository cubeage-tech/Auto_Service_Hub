package com.autoservicehub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI Vehicle Diagnosis response (SRS Section 5.1, FR-AI-01..04).
 *
 * <p><strong>This output is a recommendation only.</strong>
 * Per SRS BR-09 and FR-AI-04, AI results must be reviewed and confirmed by
 * qualified human staff before any repair action is taken.  The
 * {@code humanReviewRequired} field is always {@code true} and the
 * {@code disclaimer} field repeats this constraint in plain language.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI-generated vehicle diagnosis response. Always requires human review before action (SRS BR-09).")
public class DiagnosisResponseDTO {

    @Schema(description = "Vehicle database ID used for context enrichment (null if not supplied in request).",
            example = "3")
    private Long vehicleId;

    @Schema(description = "Human-readable summary of the most likely issue(s) identified by the AI.",
            example = "Possible engine bearing wear or loose timing chain.")
    private String possibleIssue;

    @Schema(description = "Recommended next step(s) for the mechanic to investigate or action.",
            example = "Inspect timing chain tension and engine oil quality. Perform compression test on cylinders 1-4.")
    private String recommendation;

    @Schema(
        description = "AI confidence score in the range 0.00–1.00. " +
                      "0.00 indicates the provider is not configured or confidence is unknown.",
        example = "0.82"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Plain-language disclaimer reminding staff that AI output is advisory only.",
        example = "This diagnosis is AI-generated and must be reviewed and confirmed by a qualified technician before any repair work is carried out."
    )
    private String disclaimer;

    /**
     * Always {@code true} — mandated by SRS FR-AI-04 and BR-09.
     * AI output must never automatically finalise a repair decision.
     */
    @Schema(
        description = "Always true. Indicates that a qualified human must review this result before taking action.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "Indicates whether the AI provider was unavailable at the time of this request. " +
                      "When true, possibleIssue and recommendation are informational placeholders.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(description = "UTC timestamp when the diagnosis was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;
}
