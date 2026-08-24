package com.elma.gohan.domain.risk;

/**
 * 趋势判定结果:trend 方向、severity(0~1,超阈幅度归一,连续风险函数用)、
 * 以及两个窗口的实际样本数(样本量收缩用)。
 */
public record TrendResult(
        RecentTrend trend,
        double severity,
        int recentCount,
        int baselineCount
) {

    public TrendResult {
        severity = Math.max(0.0, Math.min(1.0, severity));
    }

    public static TrendResult unknown(int recentCount, int baselineCount) {
        return new TrendResult(RecentTrend.UNKNOWN, 0.0, recentCount, baselineCount);
    }
}
