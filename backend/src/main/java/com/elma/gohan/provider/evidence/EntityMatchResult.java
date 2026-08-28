package com.elma.gohan.provider.evidence;

import java.util.Map;

/** 单家高德餐厅与百度门店的匹配结果。 */
public record EntityMatchResult(
        EntityMatchStatus status,
        Double confidence,
        PlatformEvidence evidence,
        Map<String, Double> features
) {
    public EntityMatchResult {
        status = status == null ? EntityMatchStatus.NO_MATCH : status;
        features = features == null ? Map.of() : Map.copyOf(features);
    }

    public static EntityMatchResult unavailable() {
        return new EntityMatchResult(EntityMatchStatus.UNAVAILABLE, null, null, Map.of());
    }

    public static EntityMatchResult noMatch() {
        return new EntityMatchResult(EntityMatchStatus.NO_MATCH, null, null, Map.of());
    }

    public static EntityMatchResult noMatch(Map<String, Double> features) {
        return new EntityMatchResult(EntityMatchStatus.NO_MATCH, null, null, features);
    }
}
