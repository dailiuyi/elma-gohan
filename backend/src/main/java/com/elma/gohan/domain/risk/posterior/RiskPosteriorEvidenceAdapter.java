package com.elma.gohan.domain.risk.posterior;

import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskFactors;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only adapter from today's Restaurant/Evidence/Risk snapshots to the
 * isolated RiskPosterior v0.5 calculation core.
 *
 * <p>Only continuous rating harm, review-rating harm and sample-backed trend
 * harm enter the posterior. Template and burst signals reduce review trust.
 * Data-insufficient, relative-price and legacy cross-platform conflict scores
 * intentionally do not become harm factors.</p>
 */
public final class RiskPosteriorEvidenceAdapter {

    private static final double LOG_2 = Math.log(2.0);
    private final RiskPosteriorConfig posteriorConfig;
    private final RiskPosteriorCalculator calculator;
    private final RiskPosteriorEvidenceConfig config;
    private final Clock clock;

    public RiskPosteriorEvidenceAdapter(RiskPosteriorConfig posteriorConfig,
                                        RiskPosteriorEvidenceConfig evidenceConfig) {
        this(posteriorConfig, evidenceConfig, Clock.systemUTC());
    }

    public RiskPosteriorEvidenceAdapter(RiskPosteriorConfig posteriorConfig,
                                        RiskPosteriorEvidenceConfig evidenceConfig,
                                        Clock clock) {
        this.posteriorConfig = Objects.requireNonNull(posteriorConfig, "posteriorConfig");
        this.calculator = new RiskPosteriorCalculator(posteriorConfig);
        this.config = Objects.requireNonNull(evidenceConfig, "evidenceConfig");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Explicit replay boundary; existing callers can continue using the Clock-backed overload. */
    public RiskPosteriorBatchResult calculateBatch(
            List<Restaurant> restaurants,
            Map<String, EvidenceBundle> evidenceByPoiId,
            Map<String, RiskResult> currentRiskByPoiId,
            Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return new RiskPosteriorEvidenceAdapter(posteriorConfig, config,
                Clock.fixed(asOf, ZoneOffset.UTC))
                .calculateBatch(restaurants, evidenceByPoiId, currentRiskByPoiId);
    }

    public RiskPosteriorBatchResult calculateBatch(
            List<Restaurant> restaurants,
            Map<String, EvidenceBundle> evidenceByPoiId,
            Map<String, RiskResult> currentRiskByPoiId) {
        Objects.requireNonNull(restaurants, "restaurants");
        Objects.requireNonNull(evidenceByPoiId, "evidenceByPoiId");
        Objects.requireNonNull(currentRiskByPoiId, "currentRiskByPoiId");

        List<Restaurant> stable = restaurants.stream()
                .map(restaurant -> Objects.requireNonNull(restaurant, "restaurant"))
                .sorted(Comparator.comparing(Restaurant::sourcePoiId))
                .toList();
        rejectDuplicatePoiIds(stable);
        BiasSnapshot bias = ratingBias(stable, evidenceByPoiId);
        Map<String, RiskPosteriorResult> results = new LinkedHashMap<>();
        Map<String, RiskPosteriorInput> inputs = new LinkedHashMap<>();
        for (Restaurant restaurant : stable) {
            String poiId = restaurant.sourcePoiId();
            EvidenceBundle bundle = evidenceByPoiId.get(poiId);
            RiskResult risk = currentRiskByPoiId.get(poiId);
            RiskPosteriorInput input = toInput(bundle, risk, bias.value());
            inputs.put(poiId, input);
            results.put(poiId, calculator.calculate(input));
        }
        return new RiskPosteriorBatchResult(
                round(bias.value()), bias.pairCount(), results, inputs);
    }

    private RiskPosteriorInput toInput(EvidenceBundle bundle, RiskResult risk, double ratingBias) {
        RiskPosteriorEvidenceConfig.Weights weights = config.weights();
        List<RiskPosteriorFactor> factors = new ArrayList<>(4);
        double residualTrust = residualTrust(bundle, ratingBias);
        factors.add(platformRating("amap_rating", bundle == null ? null : bundle.amap(),
                config.rating().amapSourceTrust(), 1.0, residualTrust,
                0.0, weights.amapRating()));
        factors.add(baiduRating(bundle, ratingBias, residualTrust, weights.baiduRating()));

        RestaurantEvidence reviews = bundle == null ? null : bundle.reviewEvidence();
        if (risk == null) {
            factors.add(unassessedReviews("review_rating", reviews, weights.reviewRating()));
            factors.add(unassessedReviews("trend", reviews, weights.trend()));
            return new RiskPosteriorInput(factors);
        }
        RiskFactors currentFactors = risk.factors();
        double reviewTrust = reviewTrust(currentFactors);
        factors.add(reviewRating(reviews, reviewTrust, weights.reviewRating()));
        factors.add(trend(reviews, currentFactors, reviewTrust, weights.trend()));
        return new RiskPosteriorInput(factors);
    }

    private RiskPosteriorFactor baiduRating(EvidenceBundle bundle, double ratingBias,
                                            double residualTrust, double weight) {
        if (bundle == null) return RiskPosteriorFactor.unavailable("baidu_rating", weight);
        if (bundle.entityMatch().status() == EntityMatchStatus.UNAVAILABLE) {
            return RiskPosteriorFactor.unavailable("baidu_rating", weight);
        }
        if (bundle.entityMatch().status() != EntityMatchStatus.MATCHED) {
            return RiskPosteriorFactor.noData("baidu_rating", weight);
        }
        double matchTrust = bundle.entityMatch().confidence() == null
                ? 0.0 : clamp(bundle.entityMatch().confidence());
        return platformRating("baidu_rating", bundle.baidu(),
                config.rating().baiduSourceTrust(), matchTrust, residualTrust,
                ratingBias, weight);
    }

    private RiskPosteriorFactor platformRating(String key, PlatformEvidence evidence,
                                               double sourceTrust, double matchTrust,
                                               double residualTrust, double ratingAdjustment,
                                               double weight) {
        RiskPosteriorFactor missing = missingPlatform(key, evidence, weight);
        if (missing != null) return missing;
        double rating = clampRating(evidence.overallRating() + ratingAdjustment);
        double q = sourceTrust * matchTrust * residualTrust
                * commentVolumeTrust(evidence.commentCount())
                * freshness(evidence.observedAt(), config.rating().freshnessHalfLifeDays());
        return RiskPosteriorFactor.observed(key, ratingHarm(rating), clamp(q), weight);
    }

    private RiskPosteriorFactor missingPlatform(String key, PlatformEvidence evidence,
                                                double weight) {
        if (evidence == null || evidence.status() == EvidenceStatus.UNAVAILABLE) {
            return RiskPosteriorFactor.unavailable(key, weight);
        }
        if (evidence.status() != EvidenceStatus.AVAILABLE || !validRating(evidence.overallRating())) {
            return RiskPosteriorFactor.noData(key, weight);
        }
        return null;
    }

    private RiskPosteriorFactor reviewRating(RestaurantEvidence evidence, double reviewTrust,
                                             double weight) {
        RiskPosteriorFactor missing = missingReviews("review_rating", evidence, weight);
        if (missing != null) return missing;
        List<ReviewEvidence> rated = evidence.reviews().stream()
                .filter(review -> validRating(review.rating()))
                .toList();
        if (rated.isEmpty()) return RiskPosteriorFactor.noData("review_rating", weight);
        double freshnessTotal = rated.stream()
                .mapToDouble(review -> freshness(review.createdAt(),
                        config.reviews().freshnessHalfLifeDays()))
                .sum();
        double average = freshnessTotal == 0.0
                ? config.rating().midpoint()
                : rated.stream().mapToDouble(review -> review.rating()
                        * freshness(review.createdAt(), config.reviews().freshnessHalfLifeDays()))
                        .sum() / freshnessTotal;
        double q = reviewTrust * saturation(rated.size(), config.reviews().volumeSaturationTarget())
                * freshness(evidence.fetchedAt(), config.reviews().freshnessHalfLifeDays())
                * averageFreshness(rated, config.reviews().freshnessHalfLifeDays());
        return RiskPosteriorFactor.observed("review_rating", ratingHarm(average), clamp(q), weight);
    }

    private RiskPosteriorFactor trend(RestaurantEvidence evidence, RiskFactors factors,
                                      double reviewTrust, double weight) {
        RiskPosteriorFactor missing = missingReviews("trend", evidence, weight);
        if (missing != null) return missing;
        if (factors == null) return RiskPosteriorFactor.noData("trend", weight);
        Instant now = clock.instant();
        RiskPosteriorEvidenceConfig.Trend trend = config.trend();
        Instant recentStart = now.minus(trend.recentDays(), ChronoUnit.DAYS);
        Instant baselineStart = recentStart.minus(trend.baselineDays(), ChronoUnit.DAYS);
        long recent = evidence.reviews().stream()
                .filter(this::validTimedRating)
                .filter(review -> !review.createdAt().isBefore(recentStart)
                        && !review.createdAt().isAfter(now))
                .count();
        long baseline = evidence.reviews().stream()
                .filter(this::validTimedRating)
                .filter(review -> !review.createdAt().isBefore(baselineStart)
                        && review.createdAt().isBefore(recentStart))
                .count();
        if (recent < trend.minimumRecentReviews()
                || baseline < trend.minimumBaselineReviews()) {
            return RiskPosteriorFactor.noData("trend", weight);
        }
        double sampleTrust = Math.min(1.0, Math.min(
                recent / (double) trend.targetRecentReviews(),
                baseline / (double) trend.targetBaselineReviews()));
        double q = reviewTrust * sampleTrust
                * freshness(evidence.fetchedAt(), config.reviews().freshnessHalfLifeDays());
        return RiskPosteriorFactor.observed("trend", factors.trendRisk() / 100.0,
                clamp(q), weight);
    }

    private RiskPosteriorFactor missingReviews(String key, RestaurantEvidence evidence,
                                               double weight) {
        if (evidence == null || evidence.status() == EvidenceStatus.UNAVAILABLE) {
            return RiskPosteriorFactor.unavailable(key, weight);
        }
        if (evidence.status() != EvidenceStatus.AVAILABLE || evidence.reviews().isEmpty()) {
            return RiskPosteriorFactor.noData(key, weight);
        }
        return null;
    }

    private RiskPosteriorFactor unassessedReviews(String key, RestaurantEvidence evidence,
                                                  double weight) {
        if (evidence == null || evidence.status() == EvidenceStatus.UNAVAILABLE) {
            return RiskPosteriorFactor.unavailable(key, weight);
        }
        return RiskPosteriorFactor.noData(key, weight);
    }

    private double reviewTrust(RiskFactors factors) {
        double template = factors.templateRisk() / 100.0;
        double burst = factors.burstRisk() / 100.0;
        return clamp((1.0 - config.reviews().templateTrustPenalty() * template)
                * (1.0 - config.reviews().burstTrustPenalty() * burst));
    }

    private BiasSnapshot ratingBias(List<Restaurant> restaurants,
                                    Map<String, EvidenceBundle> evidenceByPoiId) {
        List<Double> differences = new ArrayList<>();
        for (Restaurant restaurant : restaurants) {
            EvidenceBundle bundle = evidenceByPoiId.get(restaurant.sourcePoiId());
            if (!qualifiedBiasPair(bundle)) continue;
            differences.add(bundle.amap().overallRating() - bundle.baidu().overallRating());
        }
        if (differences.size() < config.rating().minimumBiasPairs()) {
            return new BiasSnapshot(0.0, differences.size());
        }
        differences.sort(Double::compareTo);
        int middle = differences.size() / 2;
        double median = differences.size() % 2 == 1
                ? differences.get(middle)
                : (differences.get(middle - 1) + differences.get(middle)) / 2.0;
        return new BiasSnapshot(median, differences.size());
    }

    private double residualTrust(EvidenceBundle bundle, double ratingBias) {
        if (!qualifiedBiasPair(bundle)) return 1.0;
        double difference = bundle.amap().overallRating() - bundle.baidu().overallRating();
        double residual = Math.abs(difference - ratingBias);
        double scaled = residual / config.rating().residualScale();
        return 1.0 / (1.0 + scaled * scaled);
    }

    private boolean qualifiedBiasPair(EvidenceBundle bundle) {
        return bundle != null
                && bundle.entityMatch().status() == EntityMatchStatus.MATCHED
                && bundle.entityMatch().confidence() != null
                && bundle.entityMatch().confidence()
                >= config.rating().minimumBiasMatchConfidence()
                && bundle.amap() != null && bundle.baidu() != null
                && bundle.amap().status() == EvidenceStatus.AVAILABLE
                && bundle.baidu().status() == EvidenceStatus.AVAILABLE
                && validRating(bundle.amap().overallRating())
                && validRating(bundle.baidu().overallRating())
                && freshEnoughForBias(bundle.amap())
                && freshEnoughForBias(bundle.baidu());
    }

    /**
     * Bias calibration is batch-global and therefore higher leverage than one
     * candidate's rating factor. Only observations retaining the configured
     * minimum of the same freshness function used by {@link #platformRating}
     * may influence the batch median. Null/future timestamps have freshness 0.
     */
    private boolean freshEnoughForBias(PlatformEvidence evidence) {
        return freshness(evidence.observedAt(), config.rating().freshnessHalfLifeDays())
                >= config.rating().minimumBiasFreshness();
    }

    private double ratingHarm(double rating) {
        double exponent = config.rating().slope() * (rating - config.rating().midpoint());
        if (exponent >= 40.0) return 0.0;
        if (exponent <= -40.0) return 1.0;
        return 1.0 / (1.0 + Math.exp(exponent));
    }

    private double commentVolumeTrust(Integer count) {
        if (count == null) return config.rating().unknownCommentCountReliability();
        if (count <= 0) return 0.0;
        return saturation(count, config.rating().commentSaturationTarget());
    }

    private double saturation(long count, int target) {
        return 1.0 - Math.exp(-count / (double) target);
    }

    private double averageFreshness(List<ReviewEvidence> reviews, int halfLifeDays) {
        return reviews.stream().mapToDouble(review -> freshness(review.createdAt(), halfLifeDays))
                .average().orElse(0.0);
    }

    private double freshness(Instant observedAt, int halfLifeDays) {
        if (observedAt == null) return 0.0;
        Instant now = clock.instant();
        if (observedAt.isAfter(now)) return 0.0;
        double ageDays = Duration.between(observedAt, now).toSeconds() / 86400.0;
        return Math.exp(-LOG_2 * ageDays / halfLifeDays);
    }

    private boolean validTimedRating(ReviewEvidence review) {
        return review.createdAt() != null && validRating(review.rating());
    }

    private boolean validRating(Double rating) {
        return rating != null && Double.isFinite(rating) && rating >= 1.0 && rating <= 5.0;
    }

    private double clampRating(double rating) {
        return Math.max(1.0, Math.min(5.0, rating));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private void rejectDuplicatePoiIds(List<Restaurant> restaurants) {
        String previous = null;
        for (Restaurant restaurant : restaurants) {
            if (restaurant.sourcePoiId() == null || restaurant.sourcePoiId().isBlank()) {
                throw new IllegalArgumentException("restaurant sourcePoiId is required");
            }
            if (restaurant.sourcePoiId().equals(previous)) {
                throw new IllegalArgumentException("duplicate sourcePoiId: "
                        + restaurant.sourcePoiId());
            }
            previous = restaurant.sourcePoiId();
        }
    }

    private record BiasSnapshot(double value, int pairCount) { }
}
