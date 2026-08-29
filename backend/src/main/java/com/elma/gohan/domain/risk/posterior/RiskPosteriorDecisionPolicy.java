package com.elma.gohan.domain.risk.posterior;

import java.util.Objects;

/**
 * Central, deterministic decision boundary for mapping a posterior result to
 * downstream safety flags.
 *
 * <p>Confidence is checked first. An insufficiently supported result is always
 * uncertain, regardless of its point estimate or conservative bound.</p>
 */
public final class RiskPosteriorDecisionPolicy {

    private final double highThreshold;
    private final double trustedSafeThreshold;
    private final double minimumConfidence;

    public RiskPosteriorDecisionPolicy(double highThreshold,
                                       double trustedSafeThreshold,
                                       double minimumConfidence) {
        this.highThreshold = probability(highThreshold, "highThreshold");
        this.trustedSafeThreshold = probability(trustedSafeThreshold,
                "trustedSafeThreshold");
        this.minimumConfidence = probability(minimumConfidence, "minimumConfidence");
        if (trustedSafeThreshold >= highThreshold) {
            throw new IllegalArgumentException(
                    "trustedSafeThreshold must be lower than highThreshold");
        }
    }

    public static RiskPosteriorDecisionPolicy defaults() {
        // The pure prior is about 0.707 confidence while the calibrated Q80
        // boundary posterior is about 0.789. The 0.75 gate keeps prior-only
        // candidates uncertain without suppressing that safety boundary.
        return new RiskPosteriorDecisionPolicy(0.61, 0.40, 0.75);
    }

    public DecisionTier classify(RiskPosteriorResult result) {
        Objects.requireNonNull(result, "result");
        if (result.confidence() < minimumConfidence) return DecisionTier.UNCERTAIN;
        if (result.conservativeRisk() >= highThreshold) return DecisionTier.BLOCKED;
        if (result.conservativeRisk() <= trustedSafeThreshold) {
            return DecisionTier.TRUSTED_SAFE;
        }
        return DecisionTier.UNCERTAIN;
    }

    public boolean isBlocked(RiskPosteriorResult result) {
        return classify(result) == DecisionTier.BLOCKED;
    }

    public boolean isTrustedSafe(RiskPosteriorResult result) {
        return classify(result) == DecisionTier.TRUSTED_SAFE;
    }

    public boolean isUncertain(RiskPosteriorResult result) {
        return classify(result) == DecisionTier.UNCERTAIN;
    }

    public double highThreshold() {
        return highThreshold;
    }

    public double trustedSafeThreshold() {
        return trustedSafeThreshold;
    }

    public double minimumConfidence() {
        return minimumConfidence;
    }

    private static double probability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and in [0, 1]");
        }
        return value;
    }

    public enum DecisionTier {
        BLOCKED,
        TRUSTED_SAFE,
        UNCERTAIN;

        public boolean blocked() {
            return this == BLOCKED;
        }

        public boolean trustedSafe() {
            return this == TRUSTED_SAFE;
        }

        public boolean uncertain() {
            return this == UNCERTAIN;
        }
    }
}
