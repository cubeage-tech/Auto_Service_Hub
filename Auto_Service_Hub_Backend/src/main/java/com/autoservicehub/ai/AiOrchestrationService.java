package com.autoservicehub.ai;

import com.autoservicehub.entity.AiInsight;
import com.autoservicehub.repository.AiInsightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Routes AI requests to the configured provider client and persists an audit
 * trail row in ai_insights (SRS 8.2, 17 - AI Insights report, 19 - AI Data minimization).
 */
@Service
@RequiredArgsConstructor
public class AiOrchestrationService {

    private final AiProviderClient providerClient;
    private final AiInsightRepository aiInsightRepository;
    private final MechanicRecommendationService mechanicRecommendationService;

    public AiResult process(AiRequest request) {
        AiResult result = request.getFeatureType() == AiFeatureType.MECHANIC_ASSIGNMENT
                ? mechanicRecommendationService.recommend(request)
                : providerClient.invoke(request);

        AiInsight insight = new AiInsight();
        insight.setFeatureType(request.getFeatureType().name());
        insight.setResultJson(result.getDetails() == null ? null : result.getDetails().toString());
        insight.setConfidence(result.getConfidence());
        aiInsightRepository.save(insight);

        return result;
    }
}
