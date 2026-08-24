package com.elma.gohan.domain.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedRiskEngineTest {

    private final RiskProperties properties = new RiskProperties();
    private final RuleBasedRiskEngine engine = new RuleBasedRiskEngine(properties);

    @Test
    void noEvidenceLowersConfidenceAndAddsInsufficientRisk() {
        RiskResult result = engine.evaluate(TestRestaurants.full("p1", 4.6, 300),
                RestaurantEvidence.empty());

        assertThat(result.riskScore()).isBetween(0, 100);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.confidence()).isEqualTo(0.5);
        assertThat(result.factors().dataInsufficientRisk()).isEqualTo(25);
        assertThat(result.factors().crossPlatformConflictRisk()).isZero();
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("百度暂未匹配"));
        assertThat(result.algorithmVersion()).isEqualTo("risk-v0.3.1");
    }

    @Test
    void externalEvidenceChangesRiskScoreAndConfidence() {
        var restaurant = TestRestaurants.full("p1", 4.6, 300);
        RiskResult empty = engine.evaluate(restaurant, RestaurantEvidence.empty());
        List<ReviewEvidence> reviews = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 30; i++) {
            reviews.add(new ReviewEvidence("r" + i, "真实到店体验不同菜品记录" + i,
                    4.6, now.minus(35L + i, ChronoUnit.DAYS)));
        }
        RiskResult withEvidence = engine.evaluate(restaurant,
                RestaurantEvidence.available("TEST", reviews, now));

        assertThat(withEvidence.riskScore()).isNotEqualTo(empty.riskScore());
        assertThat(withEvidence.confidence()).isGreaterThan(empty.confidence());
        assertThat(withEvidence.factors().ratingRisk()).isZero();
    }

    @Test
    void scoreAndConfidenceAreAlwaysClamped() {
        properties.getWeights().setRating(5.0);
        properties.getWeights().setDataInsufficient(5.0);
        RiskResult result = engine.evaluate(TestRestaurants.full("p1", 1.0, 300)
                        .withRating(null), RestaurantEvidence.unavailable("TEST"));
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(result.confidence()).isBetween(0.0, 1.0);
        assertThat(result.factors().ratingRisk()).isEqualTo(100);
    }

    @Test
    void staleReviewEvidenceCannotReduceStructuredConfidence() {
        var restaurant = TestRestaurants.full("p1", 4.6, 300);
        RiskResult structuredOnly = engine.evaluate(restaurant, RestaurantEvidence.empty());
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        List<ReviewEvidence> reviews = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new ReviewEvidence("old" + i, "历史到店记录" + i, 4.5,
                        old.minus(i, ChronoUnit.DAYS)))
                .toList();

        RiskResult stale = engine.evaluate(restaurant,
                RestaurantEvidence.available("TEST", reviews, old));

        assertThat(stale.confidence()).isEqualTo(structuredOnly.confidence());
    }

    @Test
    void trendRiskGrowsContinuouslyWithSeverityAndSampleShrinkage() {
        var restaurant = TestRestaurants.full("p1", 4.6, 300);
        Instant now = Instant.now();
        RiskResult mild = engine.evaluate(restaurant,
                RestaurantEvidence.available("TEST", trendReviews(now, 4.6, 4.0), now));
        RiskResult severe = engine.evaluate(restaurant,
                RestaurantEvidence.available("TEST", trendReviews(now, 4.8, 2.0), now));

        assertThat(mild.factors().trendRisk())
                .isBetween(properties.getTrend().getStableRisk(),
                        properties.getTrend().getDownRisk());
        assertThat(severe.factors().trendRisk()).isGreaterThan(mild.factors().trendRisk());
        assertThat(severe.factors().trendRisk()).isLessThan(properties.getTrend().getDownRisk());
    }

    private List<ReviewEvidence> trendReviews(Instant now, double historical, double recent) {
        List<ReviewEvidence> reviews = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            reviews.add(new ReviewEvidence("h" + i, "历史到店体验" + i, historical,
                    now.minus(40L + i, ChronoUnit.DAYS)));
        }
        for (int i = 0; i < 6; i++) {
            reviews.add(new ReviewEvidence("r" + i, "近期到店体验" + i, recent,
                    now.minus(i + 1L, ChronoUnit.DAYS)));
        }
        return reviews;
    }
}
