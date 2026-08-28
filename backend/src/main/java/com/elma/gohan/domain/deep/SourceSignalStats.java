package com.elma.gohan.domain.deep;

/** 单个公开来源的信号统计。 */
public record SourceSignalStats(
        int relevantCount,
        int positiveCount,
        int negativeCount,
        int operationalCount,
        int marketingCount,
        double balance,
        String direction
) {
}
