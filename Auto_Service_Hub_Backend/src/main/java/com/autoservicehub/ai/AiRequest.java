package com.autoservicehub.ai;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;
import java.util.Set;

/**
 * Generic inbound payload for any AI feature. Concrete fields vary by
 * AiFeatureType (complaint text, image reference, vehicle/job context, etc.)
 * and are carried in `context` until the provider contract is finalized (SRS 25, Q8).
 */
@Getter
@Setter
public class AiRequest {
    private AiFeatureType featureType;
    private Long customerId;
    private Long vehicleId;
    private Long jobCardId;
    private String repairType;
    private Set<String> requiredSkills;
    private Map<String, Object> context;
}
