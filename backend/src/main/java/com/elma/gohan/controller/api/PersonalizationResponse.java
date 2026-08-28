package com.elma.gohan.controller.api;

import java.util.List;

/** 当前推荐的个性化得分、置信度和选择模式。 */
public record PersonalizationResponse(
        double tasteMatchScore,
        double confidence,
        String selectionMode,
        List<String> reasons,
        String algorithmVersion
) { }
