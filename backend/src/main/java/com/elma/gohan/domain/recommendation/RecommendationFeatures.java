package com.elma.gohan.domain.recommendation;

import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskResult;

/** 每个候选只提取一次；评分、理由及探索资格使用相同的冻结数值。 */
public record RecommendationFeatures(
        Restaurant restaurant,
        RiskResult risk,
        double quality,
        double safety,
        double taste,
        double confidence,
        double categoryWeight,
        double currentBudget,
        double currentDistance,
        double budget,
        double distance,
        boolean budgetSpecified,
        RecentFoodIndex.Features recent
) { }
