package com.elma.gohan.domain.risk.posterior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskFactors;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.provider.evidence.CrossPlatformConsistency;
import com.elma.gohan.provider.evidence.EntityMatchResult;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskPosteriorEvidenceAdapterTest {

    private final Instant now = Instant.parse("2026-08-30T00:00:00Z");
    private final RiskPosteriorEvidenceAdapter adapter = new RiskPosteriorEvidenceAdapter(
            RiskPosteriorConfig.defaults(), RiskPosteriorEvidenceConfig.defaults(),
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void batchMedianCorrectsPlatformBiasAndResidualConflictExpandsUncertainty() {
        List<Restaurant> restaurants = List.of(
                restaurant("p1", 4.6, 30), restaurant("p2", 4.5, 30),
                restaurant("p3", 4.4, 30), restaurant("outlier", 4.6, 30));
        Map<String, EvidenceBundle> evidence = Map.of(
                "p1", pairedBundle("p1", 4.6, 4.2),
                "p2", pairedBundle("p2", 4.5, 4.1),
                "p3", pairedBundle("p3", 4.4, 4.0),
                "outlier", pairedBundle("outlier", 4.6, 3.0));
        Map<String, RiskResult> risks = zeroRisks(restaurants);

        RiskPosteriorBatchResult result = adapter.calculateBatch(restaurants, evidence, risks);

        assertThat(result.amapMinusBaiduMedianBias()).isEqualTo(0.4);
        assertThat(result.calibrationPairCount()).isEqualTo(4);
        RiskPosteriorResult aligned = result.posteriors().get("p1");
        RiskPosteriorResult outlier = result.posteriors().get("outlier");
        assertThat(outlier.confidence()).isLessThan(aligned.confidence());
        assertThat(outlier.intervalWidth()).isGreaterThan(aligned.intervalWidth());
    }

    @Test
    void singlePairWithHugeDifferenceIsNotSelfCalibratedAway() {
        Restaurant restaurant = restaurant("single", 5.0, 30);
        RiskPosteriorBatchResult conflict = adapter.calculateBatch(List.of(restaurant),
                Map.of("single", pairedBundle("single", 5.0, 1.0, 0.95)),
                zeroRisks(List.of(restaurant)));
        RiskPosteriorBatchResult aligned = adapter.calculateBatch(List.of(restaurant),
                Map.of("single", pairedBundle("single", 5.0, 5.0, 0.95)),
                zeroRisks(List.of(restaurant)));

        assertThat(conflict.calibrationPairCount()).isEqualTo(1);
        assertThat(conflict.amapMinusBaiduMedianBias()).isZero();
        assertThat(conflict.posteriors().get("single").confidence())
                .isLessThan(aligned.posteriors().get("single").confidence());
        assertThat(conflict.posteriors().get("single").intervalWidth())
                .isGreaterThan(aligned.posteriors().get("single").intervalWidth());
    }

    @Test
    void lowConfidencePairDoesNotEnterCalibrationOrReduceAmapReliability() {
        Restaurant restaurant = restaurant("weak-match", 5.0, 30);
        RiskPosteriorBatchResult conflict = adapter.calculateBatch(List.of(restaurant),
                Map.of("weak-match", pairedBundle("weak-match", 5.0, 1.0, 0.50)),
                zeroRisks(List.of(restaurant)));
        RiskPosteriorBatchResult aligned = adapter.calculateBatch(List.of(restaurant),
                Map.of("weak-match", pairedBundle("weak-match", 5.0, 5.0, 0.50)),
                zeroRisks(List.of(restaurant)));

        assertThat(conflict.calibrationPairCount()).isZero();
        assertThat(conflict.amapMinusBaiduMedianBias()).isZero();
        assertThat(factor(conflict, "weak-match", "amap_rating").reliability())
                .isEqualTo(factor(aligned, "weak-match", "amap_rating").reliability());
        assertThat(conflict.posteriors().get("weak-match").effectiveEvidenceMass())
                .isCloseTo(aligned.posteriors().get("weak-match").effectiveEvidenceMass(),
                        within(1.0e-12));
        assertThat(conflict.posteriors().get("weak-match").intervalWidth())
                .isNotEqualTo(aligned.posteriors().get("weak-match").intervalWidth());
        assertThat(conflict.posteriors().get("weak-match").confidence())
                .isNotEqualTo(aligned.posteriors().get("weak-match").confidence());
    }

    @Test
    void futureAndExpiredPairsCannotPoisonBatchBiasCalibration() {
        Restaurant target = restaurant("target", 4.2, 30);
        List<Restaurant> restaurants = new ArrayList<>();
        restaurants.add(target);
        Map<String, EvidenceBundle> evidence = new LinkedHashMap<>();
        evidence.put("target", pairedBundle("target", 4.2, 4.2));
        for (int i = 1; i <= 3; i++) {
            String id = "future-" + i;
            restaurants.add(restaurant(id, 5.0, 30));
            evidence.put(id, pairedBundleAt(id, 5.0, 1.0,
                    now.plus(i, ChronoUnit.DAYS)));
        }
        for (int i = 1; i <= 3; i++) {
            String id = "expired-" + i;
            restaurants.add(restaurant(id, 5.0, 30));
            evidence.put(id, pairedBundleAt(id, 5.0, 1.0,
                    now.minus(60L + i, ChronoUnit.DAYS)));
        }

        RiskPosteriorBatchResult poisoned = adapter.calculateBatch(restaurants, evidence,
                zeroRisks(restaurants));
        RiskPosteriorBatchResult baseline = adapter.calculateBatch(List.of(target),
                Map.of("target", pairedBundle("target", 4.2, 4.2)),
                zeroRisks(List.of(target)));

        assertThat(poisoned.calibrationPairCount()).isEqualTo(1);
        assertThat(poisoned.amapMinusBaiduMedianBias()).isZero();
        assertThat(poisoned.inputs().get("target")).isEqualTo(baseline.inputs().get("target"));
        assertThat(poisoned.posteriors().get("target"))
                .isEqualTo(baseline.posteriors().get("target"));
    }

    @Test
    void unavailableAndNoDataStayUnknownEvenWhenLegacyMissingRiskIsHigh() {
        Restaurant restaurant = restaurant("unknown", 4.5, 500);
        EvidenceBundle unavailable = new EvidenceBundle(RestaurantEvidence.unavailable("REVIEWS"),
                PlatformEvidence.unavailable("AMAP"), PlatformEvidence.unavailable("BAIDU"),
                EntityMatchResult.unavailable(), CrossPlatformConsistency.unknown("unknown"));
        RiskResult legacyHighMissing = risk(new RiskFactors(0, 0, 0, 0, 100, 100));

        RiskPosteriorResult result = adapter.calculateBatch(List.of(restaurant),
                Map.of("unknown", unavailable), Map.of("unknown", legacyHighMissing))
                .posteriors().get("unknown");

        assertThat(result.posteriorMean()).isEqualTo(RiskPosteriorConfig.defaults().priorRiskMean());
        assertThat(result.confidence())
                .isCloseTo(1.0 - result.intervalWidth(), within(1.0e-12));
        assertThat(result.confidence())
                .isLessThan(RiskPosteriorDecisionPolicy.defaults().minimumConfidence());
        assertThat(result.posteriorRiskScore()).isGreaterThan(20);
        assertThat(result.observedFactorCount()).isZero();
        assertThat(result.missingFactorCount()).isEqualTo(4);
    }

    @Test
    void availableReviewsWithoutCurrentRiskCannotContributeSafeEvidence() {
        Restaurant restaurant = restaurant("unassessed-reviews", 4.5, 30);
        RestaurantEvidence safeReviews = reviews(5.0);
        EvidenceBundle withReviews = new EvidenceBundle(safeReviews,
                platform("AMAP", "unassessed-reviews", 4.5, 100), null,
                EntityMatchResult.noMatch(), CrossPlatformConsistency.unknown("no match"));
        EvidenceBundle withoutReviews = amapOnlyBundle("unassessed-reviews", 4.5, 100);

        RiskPosteriorResult unassessed = adapter.calculateBatch(List.of(restaurant),
                Map.of("unassessed-reviews", withReviews), Map.of()).posteriors()
                .get("unassessed-reviews");
        RiskPosteriorResult absent = adapter.calculateBatch(List.of(restaurant),
                Map.of("unassessed-reviews", withoutReviews), Map.of()).posteriors()
                .get("unassessed-reviews");
        RiskPosteriorResult assessed = adapter.calculateBatch(List.of(restaurant),
                Map.of("unassessed-reviews", withReviews),
                Map.of("unassessed-reviews", risk(RiskFactors.empty()))).posteriors()
                .get("unassessed-reviews");

        assertThat(unassessed).isEqualTo(absent);
        assertThat(unassessed.observedFactorCount()).isEqualTo(1);
        assertThat(unassessed.missingFactorCount()).isEqualTo(3);
        assertThat(unassessed.confidence()).isLessThan(assessed.confidence());
        assertThat(unassessed.posteriorMean()).isGreaterThan(assessed.posteriorMean());
    }

    @Test
    void continuousRatingHarmIsStrictlyMonotonicWithoutThresholdSteps() {
        List<Restaurant> restaurants = List.of(
                restaurant("high", 4.21, 20),
                restaurant("middle", 4.20, 20),
                restaurant("low", 3.50, 20));
        Map<String, EvidenceBundle> evidence = new LinkedHashMap<>();
        restaurants.forEach(restaurant -> evidence.put(restaurant.sourcePoiId(),
                amapOnlyBundle(restaurant.sourcePoiId(), restaurant.rating(), 100)));

        Map<String, RiskPosteriorResult> results = adapter.calculateBatch(
                restaurants, evidence, zeroRisks(restaurants)).posteriors();

        assertThat(results.get("high").posteriorMean())
                .isLessThan(results.get("middle").posteriorMean());
        assertThat(results.get("middle").posteriorMean())
                .isLessThan(results.get("low").posteriorMean());
    }

    @Test
    void batchReplayIsDeterministicAcrossInputAndMapOrder() {
        Restaurant first = restaurant("a", 4.6, 30);
        Restaurant second = restaurant("b", 4.0, 30);
        Map<String, EvidenceBundle> forwardEvidence = new LinkedHashMap<>();
        forwardEvidence.put("a", pairedBundle("a", 4.6, 4.2));
        forwardEvidence.put("b", pairedBundle("b", 4.0, 3.6));
        Map<String, EvidenceBundle> reverseEvidence = new LinkedHashMap<>();
        reverseEvidence.put("b", forwardEvidence.get("b"));
        reverseEvidence.put("a", forwardEvidence.get("a"));

        RiskPosteriorBatchResult forward = adapter.calculateBatch(List.of(first, second),
                forwardEvidence, zeroRisks(List.of(first, second)));
        RiskPosteriorBatchResult replay = adapter.calculateBatch(List.of(second, first),
                reverseEvidence, zeroRisks(List.of(second, first)), now);

        assertThat(replay).isEqualTo(forward);
        assertThat(forward.posteriors().keySet()).containsExactly("a", "b");
    }

    @Test
    void templateAndBurstOnlyReduceReviewTrustAndLegacyPriceOrConflictDoNotAddHarm() {
        Restaurant cheap = restaurant("cheap", 4.5, 20);
        Restaurant expensive = restaurant("expensive", 4.5, 500);
        Restaurant noisy = restaurant("noisy", 4.5, 20);
        RestaurantEvidence reviews = reviews(2.0);
        EvidenceBundle cheapBundle = reviewOnlyBundle("cheap", reviews);
        EvidenceBundle expensiveBundle = reviewOnlyBundle("expensive", reviews);
        EvidenceBundle noisyBundle = reviewOnlyBundle("noisy", reviews);
        RiskResult clean = risk(new RiskFactors(0, 0, 0, 80, 0, 0));
        RiskResult legacyMissingAndConflict = risk(new RiskFactors(0, 0, 0, 80, 100, 100));
        RiskResult distrusted = risk(new RiskFactors(0, 100, 100, 80, 100, 100));

        Map<String, RiskPosteriorResult> result = adapter.calculateBatch(
                List.of(cheap, expensive, noisy),
                Map.of("cheap", cheapBundle, "expensive", expensiveBundle,
                        "noisy", noisyBundle),
                Map.of("cheap", clean, "expensive", legacyMissingAndConflict,
                        "noisy", distrusted)).posteriors();

        assertThat(result.get("expensive")).isEqualTo(result.get("cheap"));
        assertThat(result.get("noisy").confidence()).isLessThan(result.get("cheap").confidence());
        assertThat(result.get("noisy").intervalWidth())
                .isGreaterThan(result.get("cheap").intervalWidth());
    }

    private Map<String, RiskResult> zeroRisks(List<Restaurant> restaurants) {
        Map<String, RiskResult> result = new LinkedHashMap<>();
        restaurants.forEach(restaurant -> result.put(restaurant.sourcePoiId(),
                risk(RiskFactors.empty())));
        return result;
    }

    private RiskResult risk(RiskFactors factors) {
        return new RiskResult(0, RiskLevel.LOW, 0.5, factors,
                List.of("test"), "risk-v0.3.1");
    }

    private Restaurant restaurant(String id, double rating, int price) {
        return TestRestaurants.full(id, rating, 100, price);
    }

    private EvidenceBundle pairedBundle(String id, double amapRating, double baiduRating) {
        return pairedBundle(id, amapRating, baiduRating, 0.95);
    }

    private EvidenceBundle pairedBundle(String id, double amapRating, double baiduRating,
                                        double matchConfidence) {
        return pairedBundleAt(id, amapRating, baiduRating, matchConfidence, now);
    }

    private EvidenceBundle pairedBundleAt(String id, double amapRating, double baiduRating,
                                          Instant observedAt) {
        return pairedBundleAt(id, amapRating, baiduRating, 0.95, observedAt);
    }

    private EvidenceBundle pairedBundleAt(String id, double amapRating, double baiduRating,
                                          double matchConfidence, Instant observedAt) {
        PlatformEvidence amap = platformAt("AMAP", id, amapRating, 100, observedAt);
        PlatformEvidence baidu = platformAt("BAIDU", "b-" + id, baiduRating, 100,
                observedAt);
        EntityMatchResult match = new EntityMatchResult(EntityMatchStatus.MATCHED, matchConfidence,
                baidu, Map.of("name", 1.0));
        return new EvidenceBundle(RestaurantEvidence.noData("REVIEWS"), amap, baidu, match,
                CrossPlatformConsistency.unknown("adapter ignores legacy conflict"));
    }

    private EvidenceBundle amapOnlyBundle(String id, double rating, int comments) {
        return new EvidenceBundle(RestaurantEvidence.noData("REVIEWS"),
                platform("AMAP", id, rating, comments), null, EntityMatchResult.noMatch(),
                CrossPlatformConsistency.unknown("no match"));
    }

    private EvidenceBundle reviewOnlyBundle(String id, RestaurantEvidence reviews) {
        return new EvidenceBundle(reviews, PlatformEvidence.unavailable("AMAP"), null,
                EntityMatchResult.noMatch(), CrossPlatformConsistency.unknown(id));
    }

    private PlatformEvidence platform(String source, String id, double rating, int comments) {
        return platformAt(source, id, rating, comments, now);
    }

    private PlatformEvidence platformAt(String source, String id, double rating, int comments,
                                        Instant observedAt) {
        return new PlatformEvidence(source, id, EvidenceStatus.AVAILABLE, observedAt,
                "餐厅" + id, "地址", 28.0, 112.0, rating,
                null, null, null, comments, 30, "09:00-21:00", null, null);
    }

    private RiskPosteriorFactor factor(RiskPosteriorBatchResult batch, String poiId,
                                       String key) {
        return batch.inputs().get(poiId).factors().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private RestaurantEvidence reviews(double rating) {
        List<ReviewEvidence> reviews = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            reviews.add(new ReviewEvidence("recent-" + i, "近期评论" + i, rating,
                    now.minus(i % 20 + 1L, ChronoUnit.DAYS)));
        }
        for (int i = 0; i < 30; i++) {
            reviews.add(new ReviewEvidence("baseline-" + i, "历史评论" + i, rating,
                    now.minus(40L + i, ChronoUnit.DAYS)));
        }
        return RestaurantEvidence.available("REVIEWS", reviews, now);
    }
}
