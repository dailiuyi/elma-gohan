package com.elma.gohan.application.shadow;

import com.elma.gohan.domain.recommendation.RecommendationResult;
import com.elma.gohan.domain.recommendation.UserPreference;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase-1 shadow 所需的服务端既有数据；不向前端增加任何字段。
 * preExclusionRestaurants 冻结 exclude 前顺序，eligibleRestaurants 是 exclude 后用于计算的全集，
 * 二者都不是旧算法的最终池。
 */
public record SafeRegretShadowInput(
        UUID anonymousUserId,
        UUID recommendationLogId,
        List<Restaurant> preExclusionRestaurants,
        List<Restaurant> eligibleRestaurants,
        Map<String, EvidenceBundle> evidenceByPoiId,
        Map<String, RiskResult> servedRiskByPoiId,
        UserPreference userPreference,
        RecommendationResult servedResult,
        long randomSeed,
        LocalDateTime occurredAt
) {
    public SafeRegretShadowInput {
        Objects.requireNonNull(anonymousUserId, "anonymousUserId");
        Objects.requireNonNull(recommendationLogId, "recommendationLogId");
        Objects.requireNonNull(userPreference, "userPreference");
        Objects.requireNonNull(userPreference.condition(), "userPreference.condition");
        Objects.requireNonNull(servedResult, "servedResult");
        Objects.requireNonNull(occurredAt, "occurredAt");
        preExclusionRestaurants = preExclusionRestaurants == null
                ? List.of() : List.copyOf(preExclusionRestaurants);
        eligibleRestaurants = eligibleRestaurants == null
                ? List.of() : List.copyOf(eligibleRestaurants);
        evidenceByPoiId = evidenceByPoiId == null ? Map.of() : Map.copyOf(evidenceByPoiId);
        servedRiskByPoiId = servedRiskByPoiId == null
                ? Map.of() : Map.copyOf(servedRiskByPoiId);
    }
}
