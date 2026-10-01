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
 * AI Mechanic Assignment response (SRS Section 5.4, FR-AI-17..20).
 *
 * <p><strong>This is a RECOMMENDATION ONLY. Nothing has been assigned.</strong>
 * This service never writes {@code JobCard.mechanic} and never creates a job
 * task, notification or schedule entry. The responding service advisor or
 * manager remains responsible for actually assigning the work. Per SRS BR-09 and
 * FR-AI-20 the response always carries {@code humanReviewRequired = true} and a
 * plain-language {@code disclaimer}.
 *
 * <p>All recommendation fields are nullable by design. When the provider is
 * unavailable, returns no candidate, or names a mechanic that cannot be
 * validated against the real candidate pool, no mechanic is recommended and the
 * reason appears in {@code dataLimitations}. No mechanic is ever guessed, and no
 * confidence is invented.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
    description = "AI-generated mechanic assignment recommendation. Advisory only — no assignment is " +
                  "performed automatically (SRS BR-09)."
)
public class MechanicAssignmentResponseDTO {

    @Schema(description = "Job card database ID the recommendation relates to.", example = "7")
    private Long jobCardId;

    @Schema(
        description = "Recommended mechanic's database ID. NULL when the provider returned no usable " +
                      "recommendation — no mechanic is ever guessed or defaulted.",
        example = "4"
    )
    private Long recommendedMechanicId;

    @Schema(description = "Recommended mechanic's employee code, when one was validated.",
            example = "MEC-004")
    private String recommendedMechanicEmployeeCode;

    @Schema(
        description = "Recommended mechanic's name, for display so the reviewer needs no second lookup. " +
                      "Null when no mechanic was validated.",
        example = "Ravi Kumar"
    )
    private String recommendedMechanicName;

    @Schema(
        description = "Reason the provider gave for this recommendation. Null when none was made.",
        example = "Lowest current open workload among active mechanics with comparable experience."
    )
    private String rationale;

    @Schema(
        description = "Provider confidence, 0.00–1.00. Null when the provider is unavailable. A low value " +
                      "triggers an explicit manual-review warning in dataLimitations.",
        example = "0.71"
    )
    private BigDecimal confidence;

    @Schema(
        description = "Always true (SRS BR-09 / FR-AI-20). A recommendation must be reviewed and the " +
                      "assignment made by a human.",
        example = "true"
    )
    private boolean humanReviewRequired;

    @Schema(
        description = "Always false. Confirms that this endpoint performed NO assignment: JobCard.mechanic " +
                      "was not written and no schedule entry was created.",
        example = "false"
    )
    private boolean assignmentPersisted;

    @Schema(
        description = "True when the AI provider was unavailable or no real provider is configured. When true, " +
                      "no mechanic is recommended and a manual assignment is required.",
        example = "false"
    )
    private boolean providerUnavailable;

    @Schema(
        description = "What data was missing or could not support an assignment decision — for example the " +
                      "absence of skill, rating, availability or attendance data. Non-empty whenever the " +
                      "recommendation is weak. Explains the limits of the result without inventing data.",
        example = "[\"Mechanic skill and certification data is not available, so skill fit could not be assessed.\"]"
    )
    private List<String> dataLimitations;

    @Schema(
        description = "Factual summary of the candidate pool actually found in the database. Null when the " +
                      "job card was not found.",
        example = "{\"activeCandidateCount\":3,\"jobCardStatus\":\"RECEIVED\",\"alreadyAssigned\":false}"
    )
    private CandidatePoolSummaryDTO candidatePoolSummary;

    @Schema(
        description = "Plain-language statement that no assignment has been made.",
        example = "This mechanic assignment is AI-generated and is a RECOMMENDATION ONLY. No assignment has " +
                  "been made and the job card has not been modified. A service advisor or manager must " +
                  "review this recommendation and assign the work manually."
    )
    private String disclaimer;

    @Schema(description = "Timestamp when the recommendation was generated.", example = "2026-09-29T10:15:30")
    private LocalDateTime generatedAt;

    /**
     * A factual summary of the candidate pool that existed at request time.
     * Reports real counts only — never an estimate, and never a stand-in for
     * skill or availability data the system does not hold.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Factual summary of the eligible mechanic candidate pool.")
    public static class CandidatePoolSummaryDTO {

        @Schema(description = "How many active mechanics were eligible to be considered.", example = "3")
        private int activeCandidateCount;

        @Schema(description = "Current status of the job card, e.g. RECEIVED, INSPECTION, IN_REPAIR.",
                example = "RECEIVED")
        private String jobCardStatus;

        @Schema(description = "Whether the job card already has a mechanic assigned.", example = "false")
        private boolean alreadyAssigned;
    }

    /**
     * One candidate mechanic considered, with real data only. Display-only.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One candidate mechanic considered for the assignment.")
    public static class CandidateDTO {

        @Schema(description = "Mechanic database ID.", example = "4")
        private Long mechanicId;

        @Schema(description = "Employee code.", example = "MEC-004")
        private String employeeCode;

        @Schema(description = "Mechanic name, for display in the review UI.", example = "Ravi Kumar")
        private String name;

        @Schema(description = "Years of experience as recorded in the system.", example = "8")
        private Integer experienceYears;

        @Schema(
            description = "Number of job cards this mechanic currently holds (any status other than the " +
                          "terminal DELIVERED state). This is a real count from the job card table, not an " +
                          "estimate, and it does not account for shift hours or capacity.",
            example = "2"
        )
        private long openJobCards;
    }
}
