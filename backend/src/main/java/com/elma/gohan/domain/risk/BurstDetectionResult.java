package com.elma.gohan.domain.risk;

/** 评论时间集中爆发的检测结果。 */
public record BurstDetectionResult(int burstRisk, int peakCount, double peakBaselineRatio) {
    public static BurstDetectionResult none() { return new BurstDetectionResult(0, 0, 0.0); }
}
