package com.elma.gohan.domain.risk;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.provider.evidence.ConsistencyLevel;
import com.elma.gohan.provider.evidence.CrossPlatformConsistency;
import com.elma.gohan.provider.evidence.EntityMatchResult;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import org.springframework.stereotype.Component;

/** 比较已匹配平台的综合评分并量化冲突。 */
@Component
public class CrossPlatformConsistencyAnalyzer {

    private final RiskProperties properties;

    public CrossPlatformConsistencyAnalyzer(RiskProperties properties) {
        this.properties = properties;
    }

    public CrossPlatformConsistency analyze(PlatformEvidence amap, EntityMatchResult match) {
        if (match == null || match.status() == EntityMatchStatus.UNAVAILABLE) {
            return CrossPlatformConsistency.unknown("百度证据服务暂不可用");
        }
        if (match.status() == EntityMatchStatus.AMBIGUOUS) {
            return CrossPlatformConsistency.unknown("百度存在多个相似门店，暂不判断评分分歧");
        }
        if (match.status() != EntityMatchStatus.MATCHED || match.evidence() == null) {
            return CrossPlatformConsistency.unknown("百度暂未匹配到同一门店");
        }
        PlatformEvidence baidu = match.evidence();
        if (amap == null || amap.overallRating() == null || baidu.overallRating() == null) {
            return CrossPlatformConsistency.unknown("两平台综合评分数据不完整");
        }
        double difference = round(Math.abs(amap.overallRating() - baidu.overallRating()));
        RiskProperties.CrossPlatform config = properties.getCrossPlatform();
        int risk;
        ConsistencyLevel level;
        if (difference <= config.getConsistentMaxDifference()) {
            risk = 0;
            level = ConsistencyLevel.CONSISTENT;
        } else if (difference < config.getConflictStartDifference()) {
            risk = linear(difference, config.getConsistentMaxDifference(),
                    config.getConflictStartDifference(), 0, config.getConflictStartRisk());
            level = ConsistencyLevel.SLIGHT_DIFFERENCE;
        } else if (difference < config.getFullRiskDifference()) {
            risk = linear(difference, config.getConflictStartDifference(),
                    config.getFullRiskDifference(), config.getConflictStartRisk(), 100);
            level = ConsistencyLevel.CONFLICT;
        } else {
            risk = 100;
            level = ConsistencyLevel.CONFLICT;
        }
        String reason;
        if (level == ConsistencyLevel.CONSISTENT) {
            reason = "高德与百度评分接近";
        } else if (amap.overallRating() > baidu.overallRating()) {
            reason = "高德评分比百度高 " + format(difference) + " 分";
        } else if (amap.overallRating() < baidu.overallRating()) {
            reason = "百度评分比高德高 " + format(difference) + " 分";
        } else {
            reason = "高德与百度评分一致";
        }
        return new CrossPlatformConsistency(level, difference, risk, reason);
    }

    private int linear(double value, double start, double end, int from, int to) {
        return Math.max(0, Math.min(100, (int) Math.round(from
                + (value - start) * (to - from) / (end - start))));
    }

    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.1f", value); }
}
