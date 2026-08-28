package com.elma.gohan.controller.api;

/** 行为记录结果。 */
public record BehaviorResponse(
        String eventId,
        String recommendationId,
        String restaurantId,
        String type,
        String recordedAt,
        boolean deduplicated
) { }
