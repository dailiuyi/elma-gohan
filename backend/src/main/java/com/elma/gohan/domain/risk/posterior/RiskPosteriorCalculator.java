package com.elma.gohan.domain.risk.posterior;

import java.util.Objects;
import org.apache.commons.math3.distribution.BetaDistribution;

/**
 * Deterministic Beta pseudo-count aggregator for RiskPosterior v0.5.
 *
 * <p>Each observed factor contributes evidence mass {@code S * weight * q}; h
 * splits that mass between risky alpha and safe beta pseudo-counts. Missing
 * factors contribute neither side, so they leave the result at the configured
 * prior instead of masquerading as low-risk observations.</p>
 *
 * <p>The interval and conservative risk are exact quantiles of the Beta
 * posterior. With the default policy, the lower endpoint is Q20 and the upper
 * endpoint/conservative risk is Q80. Confidence is one minus that interval's
 * width; the posterior probability support is already normalized to [0, 1].</p>
 */
public final class RiskPosteriorCalculator {

    private final RiskPosteriorConfig config;

    public RiskPosteriorCalculator(RiskPosteriorConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public RiskPosteriorResult calculate(RiskPosteriorInput input) {
        Objects.requireNonNull(input, "input");
        StableSum riskyEvidence = new StableSum();
        StableSum safeEvidence = new StableSum();
        StableSum totalEvidence = new StableSum();
        int observed = 0;
        int missing = 0;

        for (RiskPosteriorFactor factor : input.factors()) {
            if (!factor.observed()) {
                missing++;
                continue;
            }
            observed++;
            double mass = config.evidenceStrength() * factor.weight() * factor.reliability();
            totalEvidence.add(mass);
            riskyEvidence.add(mass * factor.riskIntensity());
            safeEvidence.add(mass * (1.0 - factor.riskIntensity()));
        }

        double evidenceMass = totalEvidence.value();
        double alpha = config.priorRiskMean() * config.priorStrength() + riskyEvidence.value();
        double beta = (1.0 - config.priorRiskMean()) * config.priorStrength()
                + safeEvidence.value();
        double mean = alpha / (alpha + beta);
        BetaDistribution distribution = new BetaDistribution(alpha, beta);
        double lower = distribution.inverseCumulativeProbability(
                1.0 - config.conservativeQuantile());
        double upper = distribution.inverseCumulativeProbability(
                config.conservativeQuantile());
        // The Beta posterior is defined on [0, 1], so its credible-interval
        // width is already normalized to the probability support.
        double confidence = 1.0 - (upper - lower);

        return new RiskPosteriorResult(config.algorithmVersion(), alpha, beta, mean,
                clamp(confidence), lower, upper, upper, evidenceMass, observed, missing);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Kahan summation plus stable factor ordering keeps replay results deterministic. */
    private static final class StableSum {
        private double value;
        private double correction;

        void add(double next) {
            double adjusted = next - correction;
            double updated = value + adjusted;
            correction = (updated - value) - adjusted;
            value = updated;
        }

        double value() {
            return value;
        }
    }
}
