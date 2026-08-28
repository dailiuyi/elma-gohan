package com.elma.gohan.provider.evidence;

/** 已匹配平台评分差异及冲突风险。 */
public record CrossPlatformConsistency(
        ConsistencyLevel level,
        Double ratingDifference,
        int crossPlatformConflictRisk,
        String reason
) {
    public CrossPlatformConsistency {
        level = level == null ? ConsistencyLevel.UNKNOWN : level;
        crossPlatformConflictRisk = Math.max(0, Math.min(100, crossPlatformConflictRisk));
        reason = reason == null ? "跨平台一致性暂不可判断" : reason;
    }

    public static CrossPlatformConsistency unknown(String reason) {
        return new CrossPlatformConsistency(ConsistencyLevel.UNKNOWN, null, 0, reason);
    }
}
