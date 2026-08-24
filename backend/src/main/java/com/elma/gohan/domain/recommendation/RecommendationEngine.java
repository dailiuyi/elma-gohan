package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 推荐引擎抽象:输入候选(已含风险结果),输出有序候选池。
 */
public interface RecommendationEngine {

    default RecommendationResult recommend(List<Restaurant> candidates,
                                            Map<String, RiskResult> risks,
                                            UserPreference preference) {
        long seed;
        do {
            seed = ThreadLocalRandom.current().nextLong();
        } while (seed == 0L);
        return recommend(candidates, risks, preference, seed);
    }

    RecommendationResult recommend(List<Restaurant> candidates,
                                   Map<String, RiskResult> risks,
                                   UserPreference preference,
                                   long seed);

    List<SelectionCandidate> replaySelection(List<SelectionCandidate> selectionSnapshot,
                                             int poolSize,
                                             long seed);

    /** 高风险(61+)不主动推荐。 */
    default boolean isBlocked(RiskResult risk) {
        return risk.riskLevel() == RiskLevel.HIGH;
    }
}
