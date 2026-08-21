package com.elma.gohan.controller.api;

public record BehaviorResponse(
        String eventId,
        String recommendationId,
        String restaurantId,
        String type,
        String recordedAt,
        boolean deduplicated
) { }
