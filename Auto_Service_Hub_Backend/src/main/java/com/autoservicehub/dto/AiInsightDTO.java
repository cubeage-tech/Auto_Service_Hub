package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One stored AI insight (FR-REP-9). Mirrors the {@code ai_insights} table
 * without exposing the owning entity or any internal id beyond its own.
 */
@Getter
@Setter
public class AiInsightDTO {

    private Long id;

    /** Which feature produced it, e.g. DAMAGE_DETECTION or DIAGNOSIS. */
    private String featureType;

    /** Whatever the calling feature recorded as its subject. */
    private String inputRef;

    /** The provider's result, exactly as stored. */
    private String resultJson;

    private BigDecimal confidence;

    private LocalDateTime createdAt;
}
