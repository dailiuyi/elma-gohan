package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.List;

/**
 * 进入候选池的餐厅:携带风险结果、LowRegretScore 和服务端推荐理由。
 */
public record RestaurantCandidate(
        Restaurant restaurant,
        RiskResult risk,
        double lowRegretScore,
        List<String> reasons,
        PersonalizationSnapshot personalization
) {
    public RestaurantCandidate(Restaurant restaurant, RiskResult risk, double lowRegretScore,
            List<String> reasons) {
        this(restaurant, risk, lowRegretScore, reasons,
                PersonalizationSnapshot.neutral("taste-v0.1"));
    }
}
