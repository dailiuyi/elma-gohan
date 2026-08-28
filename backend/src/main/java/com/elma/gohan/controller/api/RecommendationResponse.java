package com.elma.gohan.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 严格对齐 contracts/openapi.yaml 的 RecommendationResponse。 */
public record RecommendationResponse(
        String recommendationId,
        RestaurantSummary restaurant,
        RiskAssessment risk,
        EvidenceSummaryResponse evidenceSummary,
        PersonalizationResponse personalization,
        List<String> reasons,
        @JsonInclude(JsonInclude.Include.NON_NULL) SearchNotice searchNotice,
        int alternativesRemaining
) {
    public RecommendationResponse(String recommendationId, RestaurantSummary restaurant,
            RiskAssessment risk, EvidenceSummaryResponse evidenceSummary,
            List<String> reasons, int alternativesRemaining) {
        this(recommendationId, restaurant, risk, evidenceSummary, null, reasons,
                null, alternativesRemaining);
    }
}
