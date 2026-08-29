package com.elma.gohan.domain.risk.posterior;

import java.util.Objects;

/** Immutable, code-local policy for the isolated RiskPosterior v0.5 core. */
public record RiskPosteriorConfig(
        String algorithmVersion,
        double priorRiskMean,
        double priorStrength,
        double evidenceStrength,
        double conservativeQuantile
) {

    public RiskPosteriorConfig {
        algorithmVersion = Objects.requireNonNull(algorithmVersion, "algorithmVersion").trim();
        if (algorithmVersion.isEmpty()) {
            throw new IllegalArgumentException("algorithmVersion must not be blank");
        }
        requireFinite(priorRiskMean, "priorRiskMean");
        requirePositive(priorStrength, "priorStrength");
        requirePositive(evidenceStrength, "evidenceStrength");
        requireFinite(conservativeQuantile, "conservativeQuantile");
        if (conservativeQuantile <= 0.5 || conservativeQuantile >= 1.0) {
            throw new IllegalArgumentException(
                    "conservativeQuantile must be in (0.5, 1.0)");
        }
        if (priorRiskMean <= 0.0 || priorRiskMean >= 1.0) {
            throw new IllegalArgumentException("priorRiskMean must be in (0, 1)");
        }
        double priorAlpha = priorRiskMean * priorStrength;
        double priorBeta = (1.0 - priorRiskMean) * priorStrength;
        // Keeps the posterior away from singular beta shapes and the inverse CDF stable.
        if (!Double.isFinite(priorAlpha) || !Double.isFinite(priorBeta)
                || priorAlpha < 1.0 || priorBeta < 1.0) {
            throw new IllegalArgumentException("prior alpha and beta must each be at least one");
        }
    }

    public static RiskPosteriorConfig defaults() {
        return new RiskPosteriorConfig("risk-v0.5",
                0.40, 8.0, 32.0, 0.80);
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void requirePositive(double value, String field) {
        requireFinite(value, field);
        if (value <= 0.0) throw new IllegalArgumentException(field + " must be greater than zero");
    }
}
