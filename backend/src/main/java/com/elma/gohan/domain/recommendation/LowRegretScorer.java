package com.elma.gohan.domain.recommendation;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 组合质量、安全、偏好、预算、距离和近期多样性的排序器。 */
@Component
public class LowRegretScorer {
    private final RecommendationProperties props;
    private final TasteProperties tasteProperties;

    @Autowired
    public LowRegretScorer(RecommendationProperties props, TasteProperties tasteProperties) {
        this.props = props; this.tasteProperties = tasteProperties;
    }
    public LowRegretScorer(RecommendationProperties props) { this(props, new TasteProperties()); }

    public double score(Restaurant r, RiskResult risk, SearchCondition condition) {
        return score(r, risk, new UserPreference(condition));
    }
    public double score(Restaurant r, RiskResult risk, UserPreference preference) {
        return details(r, risk, preference).score();
    }
    public List<String> reasons(Restaurant r, RiskResult risk, SearchCondition condition) {
        return reasons(r, risk, new UserPreference(condition));
    }
    public List<String> reasons(Restaurant r, RiskResult risk, UserPreference preference) {
        return details(r, risk, preference).reasons();
    }

    public PersonalizedScore details(Restaurant r, RiskResult risk, UserPreference preference) {
        SearchCondition condition = preference.condition();
        TasteProfile profile = preference.tasteProfile();
        double confidence = profile.confidence(tasteProperties);
        double quality = quality(r, props.getWeights().getRestaurantQuality());
        double safety = riskSafety(risk);
        double taste = tasteMatch(r, profile, preference.flavorTags(r), confidence);
        double budget = 0.60 * currentBudget(r, condition)
                + 0.40 * historical(profile.priceWeight(r, tasteProperties), confidence);
        double distance = 0.60 * currentDistance(r, condition)
                + 0.40 * historical(profile.distanceWeight(r, tasteProperties), confidence);
        double diversity = preference.recentHistory().diversityScore(r);
        double recentPenalty = preference.recentHistory().penalty(r);
        RecommendationProperties.Weights w = props.getWeights();
        double weighted = (w.getRestaurantQuality() * quality
                + w.getRiskSafety() * safety
                + w.getTasteMatch() * taste
                + w.getBudgetFit() * budget
                + w.getDistanceFit() * distance
                + w.getRecentDiversity() * diversity) / 100.0;
        double score = clamp(weighted - recentPenalty, 0, 100);
        List<String> personal = personalizationReasons(r, profile, confidence, budget,
                distance, recentPenalty);
        List<String> reasons = new ArrayList<>();
        if (risk.riskLevel() == RiskLevel.LOW) reasons.add("客观风险较低");
        boolean budgetSpecified = condition.minBudget() != null || condition.maxBudget() != null;
        if (budgetSpecified && currentBudget(r, condition) >= 60 && r.averagePrice() != null) {
            reasons.add("预算合适");
        }
        if (currentDistance(r, condition) >= 60) reasons.add("距离符合当前选择");
        if (r.categoryConfidence() == CategoryConfidence.INFERRED) reasons.add("餐饮信息相对有限");
        for (String reason : personal) {
            if (!reasons.contains(reason)) reasons.add(reason);
        }
        if (reasons.isEmpty()) reasons.add("综合匹配度较高");
        if (reasons.size() > 4) reasons = new ArrayList<>(reasons.subList(0, 4));
        return new PersonalizedScore(score, taste, confidence, List.copyOf(reasons),
                personal, new ScoreBreakdown(quality, safety, taste, budget, distance,
                        diversity, recentPenalty, 0));
    }

    public PersonalizedScore withExploration(PersonalizedScore base) {
        double bonus = props.getExplorationBonus();
        ScoreBreakdown b = base.breakdown();
        List<String> personal = new ArrayList<>(base.personalizationReasons());
        personal.add(0, "低风险的新类型尝试");
        List<String> reasons = new ArrayList<>(base.reasons());
        if (!reasons.contains("低风险的新类型尝试")) reasons.add(0, "低风险的新类型尝试");
        if (reasons.size() > 4) reasons = new ArrayList<>(reasons.subList(0, 4));
        return new PersonalizedScore(clamp(base.score() + bonus, 0, 100),
                base.tasteMatchScore(), base.confidence(), List.copyOf(reasons),
                List.copyOf(personal), new ScoreBreakdown(b.restaurantQuality(), b.riskSafety(),
                b.tasteMatch(), b.budgetFit(), b.distanceFit(), b.recentDiversity(),
                b.recentPenalty(), bonus));
    }

    private double quality(Restaurant r, double qualityWeight) {
        double rating = r.rating() == null ? 40 : clamp(r.rating() / 5.0 * 100, 0, 100);
        double completeness = switch (r.dataCompleteness()) {
            case FULL -> 100; case PARTIAL -> 60; case MINIMAL -> 30;
        };
        double penalty = switch (r.categoryConfidence() == null
                ? CategoryConfidence.SUPPORTED : r.categoryConfidence()) {
            case VERIFIED -> props.getCategoryConfidencePenalty().getVerified();
            case SUPPORTED -> props.getCategoryConfidencePenalty().getSupported();
            case INFERRED -> props.getCategoryConfidencePenalty().getInferred();
        };
        double componentPenalty = penalty * 100.0 / Math.max(0.0001, qualityWeight);
        return clamp(0.80 * rating + 0.20 * completeness - componentPenalty, 0, 100);
    }
    private double riskSafety(RiskResult risk) {
        double effective = risk.confidence() * risk.riskScore()
                + (1 - risk.confidence()) * props.getUncertaintyRisk();
        return 100 - clamp(effective, 0, 100);
    }
    private double tasteMatch(Restaurant r, TasteProfile p, Set<FlavorTag> flavors, double confidence) {
        double category = rawPreference(p.categoryWeight(r));
        double flavor = 50;
        if (flavors != null && !flavors.isEmpty()) {
            flavor = flavors.stream().mapToDouble(tag -> rawPreference(p.flavorWeight(tag))).average().orElse(50);
        }
        double raw = 0.65 * category + 0.35 * flavor;
        return 50 + (raw - 50) * confidence;
    }
    private double currentBudget(Restaurant r, SearchCondition c) {
        if (r.averagePrice() == null) return 50;
        if ((c.minBudget() != null && r.averagePrice() <= c.minBudget())
                || (c.maxBudget() != null && r.averagePrice() > c.maxBudget())) return 0;
        if (c.minBudget() != null && c.maxBudget() == null) return 100;
        if (c.maxBudget() == null) return 80;
        int lower = c.minBudget() == null ? 0 : c.minBudget();
        return 100 - 50.0 * (r.averagePrice() - lower) / Math.max(1, c.maxBudget() - lower);
    }
    private double currentDistance(Restaurant r, SearchCondition c) {
        int lower = c.minDistance() == null ? 0 : c.minDistance();
        return clamp(100.0 * (1.0 - (r.distanceMeters() - lower)
                / (double) Math.max(1, c.radius() - lower)), 0, 100);
    }
    private double historical(double weight, double confidence) {
        return 50 + (rawPreference(weight) - 50) * confidence;
    }
    private double rawPreference(double weight) {
        return 50 + 50 * clamp(weight / Math.max(0.0001, tasteProperties.getMaxAbsoluteWeight()), -1, 1);
    }
    private List<String> personalizationReasons(Restaurant r, TasteProfile profile,
            double confidence, double budget, double distance, double recentPenalty) {
        List<String> reasons = new ArrayList<>();
        if (confidence > 0 && profile.categoryWeight(r) > 0.3) reasons.add("你过去对这类餐厅反馈不错");
        if (confidence > 0 && budget >= 60) reasons.add("价格符合你常用预算");
        if (confidence > 0 && distance >= 60) reasons.add("距离符合你的常用范围");
        if (recentPenalty == 0) reasons.add("最近几天没有吃过类似类型");
        return reasons.size() > 3 ? List.copyOf(reasons.subList(0, 3)) : List.copyOf(reasons);
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
