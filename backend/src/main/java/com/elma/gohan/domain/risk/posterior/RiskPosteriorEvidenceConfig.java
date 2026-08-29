package com.elma.gohan.domain.risk.posterior;

/** Code-local Evidence mapping policy for the isolated posterior adapter. */
public record RiskPosteriorEvidenceConfig(
        Rating rating,
        Reviews reviews,
        Trend trend,
        Weights weights
) {

    public RiskPosteriorEvidenceConfig {
        if (rating == null || reviews == null || trend == null || weights == null) {
            throw new IllegalArgumentException("posterior evidence config sections are required");
        }
    }

    public static RiskPosteriorEvidenceConfig defaults() {
        return new RiskPosteriorEvidenceConfig(
                new Rating(4.15, 3.0, 40, 0.25, 30,
                        0.75, 0.85, 0.35, 3, 0.78, 0.25),
                new Reviews(30, 120, 0.75, 0.75),
                new Trend(30, 90, 5, 10, 15, 30),
                new Weights(0.30, 0.30, 0.20, 0.20));
    }

    public record Rating(
            double midpoint,
            double slope,
            int commentSaturationTarget,
            double unknownCommentCountReliability,
            int freshnessHalfLifeDays,
            double amapSourceTrust,
            double baiduSourceTrust,
            double residualScale,
            int minimumBiasPairs,
            double minimumBiasMatchConfidence,
            double minimumBiasFreshness
    ) {
        public Rating {
            finiteInRange(midpoint, 1.0, 5.0, "rating.midpoint");
            positive(slope, "rating.slope");
            positive(commentSaturationTarget, "rating.commentSaturationTarget");
            finiteInRange(unknownCommentCountReliability, 0.0, 1.0,
                    "rating.unknownCommentCountReliability");
            positive(freshnessHalfLifeDays, "rating.freshnessHalfLifeDays");
            finiteInRange(amapSourceTrust, 0.0, 1.0, "rating.amapSourceTrust");
            finiteInRange(baiduSourceTrust, 0.0, 1.0, "rating.baiduSourceTrust");
            positive(residualScale, "rating.residualScale");
            positive(minimumBiasPairs, "rating.minimumBiasPairs");
            finiteInRange(minimumBiasMatchConfidence, 0.0, 1.0,
                    "rating.minimumBiasMatchConfidence");
            finiteInRange(minimumBiasFreshness, 0.0, 1.0,
                    "rating.minimumBiasFreshness");
            if (minimumBiasFreshness == 0.0) {
                throw new IllegalArgumentException(
                        "rating.minimumBiasFreshness must be greater than zero");
            }
        }
    }

    public record Reviews(
            int volumeSaturationTarget,
            int freshnessHalfLifeDays,
            double templateTrustPenalty,
            double burstTrustPenalty
    ) {
        public Reviews {
            positive(volumeSaturationTarget, "reviews.volumeSaturationTarget");
            positive(freshnessHalfLifeDays, "reviews.freshnessHalfLifeDays");
            finiteInRange(templateTrustPenalty, 0.0, 1.0,
                    "reviews.templateTrustPenalty");
            finiteInRange(burstTrustPenalty, 0.0, 1.0,
                    "reviews.burstTrustPenalty");
        }
    }

    public record Trend(
            int recentDays,
            int baselineDays,
            int minimumRecentReviews,
            int minimumBaselineReviews,
            int targetRecentReviews,
            int targetBaselineReviews
    ) {
        public Trend {
            positive(recentDays, "trend.recentDays");
            positive(baselineDays, "trend.baselineDays");
            positive(minimumRecentReviews, "trend.minimumRecentReviews");
            positive(minimumBaselineReviews, "trend.minimumBaselineReviews");
            if (targetRecentReviews < minimumRecentReviews
                    || targetBaselineReviews < minimumBaselineReviews) {
                throw new IllegalArgumentException("trend targets must cover minimum samples");
            }
        }
    }

    public record Weights(
            double amapRating,
            double baiduRating,
            double reviewRating,
            double trend
    ) {
        public Weights {
            positive(amapRating, "weights.amapRating");
            positive(baiduRating, "weights.baiduRating");
            positive(reviewRating, "weights.reviewRating");
            positive(trend, "weights.trend");
            double total = amapRating + baiduRating + reviewRating + trend;
            if (Math.abs(total - 1.0) > 1.0e-9) {
                throw new IllegalArgumentException("posterior factor weights must sum to one");
            }
        }
    }

    private static void positive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(field + " must be finite and greater than zero");
        }
    }

    private static void positive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be greater than zero");
    }

    private static void finiteInRange(double value, double minimum, double maximum,
                                      String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be finite and in ["
                    + minimum + ", " + maximum + "]");
        }
    }
}
