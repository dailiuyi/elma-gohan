package com.elma.gohan.controller.api;

import java.util.List;

public record PersonalizationResponse(
        double tasteMatchScore,
        double confidence,
        String selectionMode,
        List<String> reasons,
        String algorithmVersion
) { }
