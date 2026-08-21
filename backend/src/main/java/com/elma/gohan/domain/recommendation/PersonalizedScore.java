package com.elma.gohan.domain.recommendation;

import java.util.List;

public record PersonalizedScore(
        double score,
        double tasteMatchScore,
        double confidence,
        List<String> reasons,
        List<String> personalizationReasons,
        ScoreBreakdown breakdown
) { }
