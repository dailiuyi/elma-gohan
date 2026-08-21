package com.elma.gohan.domain.recommendation;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * LowRegretScore:基础质量 / 距离 / 预算 / 品类 / 数据完整度 / 风险 六因子加权(0~100),
 * 权重全部来自 RecommendationProperties。同时生成面向用户的推荐理由(1~5 条)。
 */
@Component
public class LowRegretScorer {

    private final RecommendationProperties props;
    private final TasteProperties tasteProperties;

    @Autowired
    public LowRegretScorer(RecommendationProperties props, TasteProperties tasteProperties) {
        this.props = props;
        this.tasteProperties = tasteProperties;
    }

    public LowRegretScorer(RecommendationProperties props) {
        this(props, new TasteProperties());
    }

    public double score(Restaurant r, RiskResult risk, SearchCondition condition) {
        return score(r, risk, new UserPreference(condition));
    }

    public double score(Restaurant r, RiskResult risk, UserPreference preference) {
        SearchCondition condition = preference.condition();
        RecommendationProperties.Weights w = props.getWeights();
        double base = w.getRating() * ratingFactor(r)
                + w.getDistance() * distanceFactor(r, condition)
                + w.getBudget() * budgetFactor(r, condition)
                + w.getCategory() * categoryFactor(r, condition)
                + w.getCompleteness() * completenessFactor(r)
                + w.getRisk() * riskFactor(risk);
        return Math.max(0.0, Math.min(100.0,
                base + tasteAdjustment(r, preference.tasteProfile())
                        - categoryConfidencePenalty(r.categoryConfidence())));
    }

    public List<String> reasons(Restaurant r, RiskResult risk, SearchCondition condition) {
        return reasons(r, risk, new UserPreference(condition));
    }

    public List<String> reasons(Restaurant r, RiskResult risk, UserPreference preference) {
        SearchCondition condition = preference.condition();
        List<String> reasons = new ArrayList<>();
        if (distanceFactor(r, condition) >= 0.6) {
            reasons.add("距离近");
        }
        if (r.averagePrice() != null && budgetFactor(r, condition) > 0.0) {
            reasons.add("预算合适");
        }
        if (r.categoryConfidence() == CategoryConfidence.INFERRED) {
            reasons.add("餐饮信息相对有限");
        }
        if (r.rating() != null && r.rating() >= 4.2) {
            reasons.add("评分稳定");
        }
        if (r.dataCompleteness() == DataCompleteness.FULL) {
            reasons.add("数据完整度较高");
        }
        if (risk.riskLevel() == com.elma.gohan.domain.risk.RiskLevel.LOW) {
            reasons.add("踩坑风险低");
        }
        if (tasteAdjustment(r, preference.tasteProfile()) >= 3.0) {
            reasons.add("符合你的历史口味");
        }
        if (reasons.isEmpty()) {
            reasons.add("综合匹配度较高");
        }
        return reasons.size() > 5 ? reasons.subList(0, 5) : reasons;
    }

    private double ratingFactor(Restaurant r) {
        return r.rating() == null ? 0.3 : Math.max(0, Math.min(1, r.rating() / 5.0));
    }

    private double distanceFactor(Restaurant r, SearchCondition c) {
        int lowerBound = c.minDistance() == null ? 0 : c.minDistance();
        int interval = c.radius() - lowerBound;
        if (interval <= 0) {
            return 0.5;
        }
        return Math.max(0, Math.min(1,
                1.0 - (double) (r.distanceMeters() - lowerBound) / interval));
    }

    private double budgetFactor(Restaurant r, SearchCondition c) {
        if (r.averagePrice() == null) {
            return 0.5;
        }
        if ((c.minBudget() != null && r.averagePrice() <= c.minBudget())
                || (c.maxBudget() != null && r.averagePrice() > c.maxBudget())) {
            return 0.0;
        }
        if (c.minBudget() != null && c.maxBudget() == null) {
            return 1.0;
        }
        if (c.maxBudget() == null) {
            return 0.8;
        }
        int lowerBound = c.minBudget() == null ? 0 : c.minBudget();
        int interval = c.maxBudget() - lowerBound;
        if (interval <= 0) {
            return 0.5;
        }
        // 预算内越便宜越好:留一半分给"接近预算"的餐厅,避免只推最便宜。
        return 1.0 - 0.5 * (r.averagePrice() - lowerBound) / interval;
    }

    private double categoryFactor(Restaurant r, SearchCondition c) {
        return c.categoryUnlimited() ? 0.9 : 1.0;
    }

    private double completenessFactor(Restaurant r) {
        return switch (r.dataCompleteness()) {
            case FULL -> 1.0;
            case PARTIAL -> 0.6;
            case MINIMAL -> 0.3;
        };
    }

    private double riskFactor(RiskResult risk) {
        double effectiveRisk = risk.confidence() * risk.riskScore()
                + (1.0 - risk.confidence()) * props.getUncertaintyRisk();
        return 1.0 - Math.max(0.0, Math.min(100.0, effectiveRisk)) / 100.0;
    }

    double tasteAdjustment(Restaurant restaurant, TasteProfile profile) {
        if (profile == null || profile.feedbackCount() == 0) return 0.0;
        RecommendationProperties.Taste taste = props.getTaste();
        double maxWeight = Math.max(0.0001, tasteProperties.getMaxAbsoluteWeight());
        double normalized = taste.getCategoryWeight()
                * profile.categoryWeight(restaurant) / maxWeight
                + taste.getPriceWeight()
                * profile.priceWeight(restaurant, tasteProperties) / maxWeight
                + taste.getDistanceWeight()
                * profile.distanceWeight(restaurant, tasteProperties) / maxWeight;
        normalized = Math.max(-1.0, Math.min(1.0, normalized));
        return normalized * taste.getMaxAdjustment();
    }

    double categoryConfidencePenalty(CategoryConfidence confidence) {
        RecommendationProperties.CategoryConfidencePenalty penalty =
                props.getCategoryConfidencePenalty();
        return switch (confidence == null ? CategoryConfidence.SUPPORTED : confidence) {
            case VERIFIED -> penalty.getVerified();
            case SUPPORTED -> penalty.getSupported();
            case INFERRED -> penalty.getInferred();
        };
    }
}
