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
 * AI Damage Detection response (SRS Section 5.6, FR-AI-13..16).
 *
 * <p><strong>Text-based advisory assessment. No image was analysed.</strong>
 * This response is derived from a written description of the damage and the
 * vehicle's job context. The system has no image upload, storage or vision
 * capability, and nothing here should be read as the result of examining a
 * photograph. Per SRS BR-09 and FR-AI-16 the response always carries
 * {@code humanReviewRequired = true} and a plain-language {@code disclaimer}.
 *
 * <p>All fields are nullable by design. When the provider is unavailable or
 * returns nothing usable, no damage area is fabricated and the reason appears in
 * {@code dataLimitations}. Severity is never defaulted to a value the provider
 * did not supply, because the system defines no severity scale to default to.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    description = "AI-generated damage assessment based on written information. Advisory only — no image was " +
                  "analysed, and human review is required before use (SRS BR-09)."
)
public class DamageDetectionResponseDTO {

    @Schema(description = "Vehicle database ID the assessment relates to.", example = "3")
    private Long vehicleId;

    @Schema(description = "Job card database ID used as context, when one was supplied.", example = "7")
    private Long jobCardId;

    @Schema(
        description = "Short summary of the reported damage as understood from the written description. " +
                      "Null when the provider produced no summary.",
        example = "Damage appears concentrated on the front-left corner of the vehicle."
    )
    private String summary;

    @Schema(
        description = "Affected areas identified from the written description. Empty when the provider is " +
                      "unavailable or returned nothing. These are advisory text-derived observations, not " +
                      "inspection findings from a physical examination.",
        example = "[{\"area\":\"Front-left wing panel\",\"severity\":\"MODERATE\",\"description\":\"Surface scraping and denting\"}]"
    )
    private List<DamageAreaDTO> affectedAreas;

    @Schema(
        description = "Provider confidence, 0.00–1.00. Null when the provider is unavailable. A low value " +
                      "triggers an explicit manual-review warning in dataLimitations.",
        example = "0.62"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Always true (SRS BR-09 / FR-AI-16). A damage assessment must be reviewed by a " +
                      "qualified technician who can physically inspect the vehicle.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "True when the AI provider was unavailable or no real provider is configured. When true, " +
                      "affectedAreas is empty and no damage findings should be inferred.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(
        description = "What data was missing or could not support the assessment. Always includes the " +
                      "statement that no images were analysed, and that inspections are not linked to " +
                      "vehicles. Explains the limits of the result without inventing data.",
        example = "[\"Damage assessment is based on the reported description and job context only. No images or photographs were analysed.\"]"
    )
    private List<String> dataLimitations;

    @Schema(
        description = "Plain-language statement of what this output is and what it must not be used for.",
        example = "This damage assessment is AI-generated from a WRITTEN description and is advisory only. " +
                  "No photographs or images were analysed. It must not be used to settle an insurance claim " +
                  "and must not be used to declare a vehicle safe or unsafe."
    )
    private String disclaimer;

    @Schema(description = "Timestamp when the assessment was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;

    /**
     * One area of reported damage, as understood from the written description.
     *
     * <p>Display-only. This is never written to a job card, estimate or invoice,
     * and it is never the result of examining a photograph.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One area of reported damage identified from the written description. Advisory only.")
    public static class DamageAreaDTO {

        @Schema(description = "Name of the affected area as reported.", example = "Front-left wing panel")
        private String area;

        @Schema(
            description = "Severity as reported by the provider. NULL when the provider did not express one — " +
                          "never defaulted, because the system defines no severity scale to default to.",
            example = "MODERATE", allowableValues = {"MINOR", "MODERATE", "MAJOR", "SEVERE"})
        private String severity;

        @Schema(description = "Description of the damage in this area.", example = "Surface scraping and denting")
        private String description;

        @Schema(
            description = "Reason the provider gave for identifying this area.",
            example = "The description states the panel is scraped and dented."
        )
        private String rationale;
    }
}
