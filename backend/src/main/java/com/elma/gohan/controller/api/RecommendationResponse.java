package com.elma.gohan.controller.api;

import java.util.List;

/** 严格对齐 contracts/openapi.yaml 的 RecommendationResponse。 */
public record RecommendationResponse(
        String recommendationId,
        RestaurantSummary restaurant,
        RiskAssessment risk,
        EvidenceSummaryResponse evidenceSummary,
        PersonalizationResponse personalization,
        List<String> reasons,
        int alternativesRemaining
) {
    public RecommendationResponse(String recommendationId, RestaurantSummary restaurant,
            RiskAssessment risk, EvidenceSummaryResponse evidenceSummary,
            List<String> reasons, int alternativesRemaining) {
        this(recommendationId, restaurant, risk, evidenceSummary, null, reasons,
                alternativesRemaining);
    }
}
