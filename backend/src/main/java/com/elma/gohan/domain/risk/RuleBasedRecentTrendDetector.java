package com.elma.gohan.domain.risk;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 使用近期窗口与历史窗口比较评分趋势。 */
@Component
public class RuleBasedRecentTrendDetector implements RecentTrendDetector {

    private final RiskProperties.Trend properties;
    private final Clock clock;

    @Autowired
    public RuleBasedRecentTrendDetector(RiskProperties riskProperties) {
        this(riskProperties, Clock.systemUTC());
    }

    public RuleBasedRecentTrendDetector(RiskProperties riskProperties, Clock clock) {
        this.properties = riskProperties.getTrend();
        this.clock = clock;
    }

    @Override
    public TrendResult detect(List<ReviewEvidence> reviews) {
        Instant now = clock.instant();
        Instant recentStart = now.minus(properties.getRecentDays(), ChronoUnit.DAYS);
        Instant baselineStart = recentStart.minus(properties.getBaselineDays(), ChronoUnit.DAYS);
        List<ReviewEvidence> recent = valid(reviews).stream()
                .filter(r -> !r.createdAt().isBefore(recentStart) && !r.createdAt().isAfter(now))
                .toList();
        List<ReviewEvidence> baseline = valid(reviews).stream()
                .filter(r -> !r.createdAt().isBefore(baselineStart)
                        && r.createdAt().isBefore(recentStart))
                .toList();
        if (recent.size() < properties.getMinRecentReviews()
                || baseline.size() < properties.getMinBaselineReviews()) {
            return TrendResult.unknown(recent.size(), baseline.size());
        }
        double recentAverage = average(recent);
        double baselineAverage = average(baseline);
        double recentNegative = negativeRatio(recent);
        double baselineNegative = negativeRatio(baseline);
        double downMargin = margin(baselineAverage - recentAverage, properties.getRatingDelta(),
                recentNegative - baselineNegative, properties.getNegativeRatioDelta());
        double upMargin = margin(recentAverage - baselineAverage, properties.getRatingDelta(),
                baselineNegative - recentNegative, properties.getNegativeRatioDelta());
        if (downMargin > 0) {
            return new TrendResult(RecentTrend.DOWN, downMargin,
                    recent.size(), baseline.size());
        }
        if (upMargin > 0) {
            return new TrendResult(RecentTrend.UP, upMargin,
                    recent.size(), baseline.size());
        }
        return new TrendResult(RecentTrend.STABLE, 0.0, recent.size(), baseline.size());
    }

    private double margin(double ratingDelta, double ratingThreshold,
                          double negativeDelta, double negativeThreshold) {
        double ratingMargin = ratingThreshold > 0
                ? ratingDelta / ratingThreshold - 1.0 : 0.0;
        double negativeMargin = negativeThreshold > 0
                ? negativeDelta / negativeThreshold - 1.0 : 0.0;
        return Math.max(0.0, Math.min(1.0, Math.max(ratingMargin, negativeMargin)));
    }

    private List<ReviewEvidence> valid(List<ReviewEvidence> reviews) {
        return (reviews == null ? List.<ReviewEvidence>of() : reviews).stream()
                .filter(r -> r.createdAt() != null && r.rating() != null)
                .toList();
    }

    private double average(List<ReviewEvidence> reviews) {
        return reviews.stream().mapToDouble(ReviewEvidence::rating).average().orElse(0.0);
    }

    private double negativeRatio(List<ReviewEvidence> reviews) {
        long negative = reviews.stream()
                .filter(r -> r.rating() <= properties.getNegativeRatingMax()).count();
        return (double) negative / reviews.size();
    }
}
