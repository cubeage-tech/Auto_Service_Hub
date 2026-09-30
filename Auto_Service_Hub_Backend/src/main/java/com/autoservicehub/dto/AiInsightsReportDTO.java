package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-REP-9 / SRS AI Insights: previously generated AI insight results.
 *
 * <p>This reports what the existing AiOrchestrationService has already stored —
 * it generates nothing and calls no provider. {@code resultJson} is passed
 * through as the raw string it was stored as, because re-parsing or reshaping it
 * would risk misrepresenting a provider's own output.
 */
@Getter
@Setter
public class AiInsightsReportDTO {

    private LocalDateTime from;
    private LocalDateTime to;

    /** Insights matching the filters, newest first. */
    private List<AiInsightDTO> insights = new ArrayList<>();

    /** True when no insight matched the filters — not an error. */
    private boolean empty;
}
