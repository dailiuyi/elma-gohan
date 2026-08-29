package com.elma.gohan.domain.risk;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 过滤未来与极老时间后，以包含零评论日的时间线计算峰值窗口相对历史基线。 */
@Component
public class SlidingWindowBurstDetector implements ReviewBurstDetector {

    private final RiskProperties.Burst properties;
    private final Clock clock;

    @Autowired
    public SlidingWindowBurstDetector(RiskProperties riskProperties) {
        this(riskProperties, Clock.systemUTC());
    }

    SlidingWindowBurstDetector(RiskProperties riskProperties, Clock clock) {
        this.properties = riskProperties.getBurst();
        this.clock = clock;
    }

    @Override
    public BurstDetectionResult detect(List<ReviewEvidence> reviews) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        Instant now = clock.instant();
        Instant earliest = now.minus(Math.max(1, properties.getMaxHistoryDays()), ChronoUnit.DAYS);
        for (ReviewEvidence review : reviews == null ? List.<ReviewEvidence>of() : reviews) {
            if (review.createdAt() != null
                    && !review.createdAt().isAfter(now)
                    && !review.createdAt().isBefore(earliest)) {
                LocalDate date = review.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
                counts.merge(date, 1, Integer::sum);
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < properties.getMinReviews() || counts.isEmpty()) {
            return BurstDetectionResult.none();
        }
        LocalDate start = counts.keySet().stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = counts.keySet().stream().max(LocalDate::compareTo).orElseThrow();
        int spanDays = (int) ChronoUnit.DAYS.between(start, end) + 1;
        if (spanDays < properties.getMinHistoryDays() + properties.getWindowDays()) {
            return BurstDetectionResult.none();
        }

        int peak = 0;
        for (int offset = 0; offset <= spanDays - properties.getWindowDays(); offset++) {
            int window = 0;
            LocalDate windowStart = start.plusDays(offset);
            for (int day = 0; day < properties.getWindowDays(); day++) {
                window += counts.getOrDefault(windowStart.plusDays(day), 0);
            }
            peak = Math.max(peak, window);
        }
        if (peak < properties.getMinPeakCount()) {
            return new BurstDetectionResult(0, peak, 0.0);
        }
        int outsideDays = spanDays - properties.getWindowDays();
        double baselineWindow = outsideDays <= 0 ? 0.0
                : ((double) (total - peak) / outsideDays) * properties.getWindowDays();
        double ratio = peak / Math.max(1.0, baselineWindow);
        int risk = linearRisk(ratio, properties.getRatioStart(), properties.getRatioFull());
        return new BurstDetectionResult(risk, peak, ratio);
    }

    private int linearRisk(double value, double start, double full) {
        if (value <= start) return 0;
        if (value >= full) return 100;
        return (int) Math.round((value - start) * 100.0 / (full - start));
    }
}
