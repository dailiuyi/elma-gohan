package com.elma.gohan.domain.recommendation;

public record ScoreBreakdown(
        double restaurantQuality,
        double riskSafety,
        double tasteMatch,
        double budgetFit,
        double distanceFit,
        double recentDiversity,
        double recentPenalty,
        double explorationBonus
) { }
