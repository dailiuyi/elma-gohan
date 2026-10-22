package com.elma.gohan.application;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.controller.api.*;
import com.elma.gohan.domain.recommendation.*;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.*;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 响应只读取已冻结的分项和理由，不再计算分数或加载画像。 */
@Component
public class RecommendationResponseAssembler {
    private static final int MAX_ALTERNATIVES = 5;
    private final RecommendationProperties recommendationProperties;
    private final RecommendationSnapshotJson json;

    public RecommendationResponseAssembler(RecommendationProperties recommendationProperties, RecommendationSnapshotJson json) {
        this.recommendationProperties = recommendationProperties;
        this.json = json;
    }

    /** reroll 时从候选快照 + restaurant 表重建候选视图。 */
    public RestaurantCandidate toView(RecommendationLogEntity log,
                                       RecommendationCandidateEntity c, RestaurantEntity restaurantEntity) {
        Restaurant restaurant = restaurantEntity.toRestaurant(c.getDistanceMeters());
        RiskResult risk = new RiskResult(c.getRiskScore(), RiskLevel.valueOf(c.getRiskLevel()),
                c.getRiskConfidence(), json.read(c.getRiskFactorsJson(), RiskFactors.class),
                json.strings(c.getRiskReasonsJson()), c.getRiskAlgorithmVersion(),
                json.evidenceSummary(c.getEvidenceSummaryJson()));
        PersonalizationSnapshot personalization = new PersonalizationSnapshot(
                c.getTasteMatchScore(), c.getTasteConfidence(),
                SelectionMode.valueOf(c.getSelectionMode()),
                json.strings(c.getPersonalizationReasonsJson()), log.getTasteAlgorithmVersion(),
                json.read(c.getScoreBreakdownJson(), ScoreBreakdown.class));
        return new RestaurantCandidate(restaurant, risk, c.getLowRegretScore(),
                json.strings(c.getReasonsJson()), personalization);
    }

    public RecommendationResponse toResponse(UUID logId, RestaurantCandidate candidate,
                                              int alternativesRemaining,
                                              SearchNotice searchNotice) {
        Restaurant r = candidate.restaurant();
        int walkingMinutes = Math.max(1, (int) Math.ceil(
                r.distanceMeters() / (double) recommendationProperties.getWalkingSpeedMetersPerMinute()));
        return new RecommendationResponse(
                logId.toString(),
                new RestaurantSummary(
                        r.id().toString(), r.name(), r.latitude(), r.longitude(), r.address(),
                        new RestaurantSummary.Category(r.categoryCode(), r.categoryLabel()),
                        r.distanceMeters(), walkingMinutes, r.averagePrice(), r.rating(),
                        r.businessStatus().name()),
                new RiskAssessment(candidate.risk().riskScore(), candidate.risk().riskLevel().name(),
                        candidate.risk().confidence(),
                        candidate.risk().reasons(), candidate.risk().algorithmVersion()),
                EvidenceSummaryResponse.from(candidate.risk().evidenceSummary()),
                new PersonalizationResponse(candidate.personalization().tasteMatchScore(),
                        candidate.personalization().confidence(),
                        candidate.personalization().selectionMode().name(),
                        candidate.personalization().reasons(),
                        candidate.personalization().algorithmVersion()),
                candidate.reasons(),
                searchNotice,
                Math.min(MAX_ALTERNATIVES, Math.max(0, alternativesRemaining)));
    }

}
