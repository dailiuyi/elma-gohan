package com.elma.gohan.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedRecentTrendDetectorTest {

    private final Instant now = Instant.parse("2026-08-19T00:00:00Z");
    private final RuleBasedRecentTrendDetector detector = new RuleBasedRecentTrendDetector(
            new RiskProperties(), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void detectsDownStableUpAndUnknown() {
        assertThat(detector.detect(samples(4.8, 2.0)).trend()).isEqualTo(RecentTrend.DOWN);
        assertThat(detector.detect(samples(4.8, 2.0)).severity()).isEqualTo(1.0);
        assertThat(detector.detect(samples(4.2, 4.3)).trend()).isEqualTo(RecentTrend.STABLE);
        assertThat(detector.detect(samples(3.5, 4.5)).trend()).isEqualTo(RecentTrend.UP);
        assertThat(detector.detect(List.of()).trend()).isEqualTo(RecentTrend.UNKNOWN);
    }

    private List<ReviewEvidence> samples(double historical, double recent) {
        List<ReviewEvidence> result = new ArrayList<>();
        for (int i = 0; i < 12; i++) result.add(new ReviewEvidence("h" + i, "历史", historical,
                now.minus(40L + i, ChronoUnit.DAYS)));
        for (int i = 0; i < 6; i++) result.add(new ReviewEvidence("r" + i, "近期", recent,
                now.minus(i + 1L, ChronoUnit.DAYS)));
        return result;
    }
}
