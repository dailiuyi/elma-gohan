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

class SlidingWindowBurstDetectorTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private final SlidingWindowBurstDetector detector =
            new SlidingWindowBurstDetector(new RiskProperties(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void concentratedReviewsProduceBurstRisk() {
        Instant now = NOW;
        List<ReviewEvidence> reviews = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            reviews.add(review("n" + day, now.minus(day, ChronoUnit.DAYS)));
        }
        for (int i = 0; i < 30; i++) reviews.add(review("p" + i, now));

        BurstDetectionResult result = detector.detect(reviews);
        assertThat(result.peakCount()).isGreaterThanOrEqualTo(30);
        assertThat(result.burstRisk()).isGreaterThan(0);
    }

    @Test
    void steadyReviewsDoNotProduceBurstRisk() {
        Instant now = NOW;
        List<ReviewEvidence> reviews = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            for (int i = 0; i < 3; i++) reviews.add(review(day + "-" + i,
                    now.minus(day, ChronoUnit.DAYS)));
        }
        assertThat(detector.detect(reviews).burstRisk()).isZero();
    }

    @Test
    void futureAndExtremelyOldReviewsCannotCreateFalseBurst() {
        List<ReviewEvidence> reviews = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            reviews.add(review("steady-" + day, NOW.minus(day, ChronoUnit.DAYS)));
        }
        for (int i = 0; i < 30; i++) {
            reviews.add(review("future-" + i, NOW.plus(10, ChronoUnit.DAYS)));
        }
        reviews.add(review("ancient", NOW.minus(20_000, ChronoUnit.DAYS)));

        BurstDetectionResult result = detector.detect(reviews);

        assertThat(result.burstRisk()).isZero();
        assertThat(result.peakCount()).isEqualTo(3);
    }

    private ReviewEvidence review(String id, Instant at) {
        return new ReviewEvidence(id, "评论内容" + id, 4.0, at);
    }
}
