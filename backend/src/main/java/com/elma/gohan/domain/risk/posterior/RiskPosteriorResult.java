package com.elma.gohan.domain.risk.posterior;

/** Pure calculation result; it is not wired to the public RiskAssessment yet. */
public record RiskPosteriorResult(
        String algorithmVersion,
        double posteriorAlpha,
        double posteriorBeta,
        double posteriorMean,
        double confidence,
        double intervalLower,
        double intervalUpper,
        double conservativeRisk,
        double effectiveEvidenceMass,
        int observedFactorCount,
        int missingFactorCount
) {

    public RiskPosteriorResult {
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion must not be blank");
        }
        positive(posteriorAlpha, "posteriorAlpha");
        positive(posteriorBeta, "posteriorBeta");
        probability(posteriorMean, "posteriorMean");
        probability(confidence, "confidence");
        probability(intervalLower, "intervalLower");
        probability(intervalUpper, "intervalUpper");
        probability(conservativeRisk, "conservativeRisk");
        if (intervalLower > posteriorMean || posteriorMean > intervalUpper) {
            throw new IllegalArgumentException("posteriorMean must be inside the interval");
        }
        if (conservativeRisk < posteriorMean || conservativeRisk > intervalUpper) {
            throw new IllegalArgumentException(
                    "conservativeRisk must be between posteriorMean and intervalUpper");
        }
        if (!Double.isFinite(effectiveEvidenceMass) || effectiveEvidenceMass < 0.0) {
            throw new IllegalArgumentException("effectiveEvidenceMass must be non-negative");
        }
        if (observedFactorCount < 0 || missingFactorCount < 0) {
            throw new IllegalArgumentException("factor counts must be non-negative");
        }
    }

    public int posteriorRiskScore() {
        return percent(posteriorMean);
    }

    public int conservativeRiskScore() {
        return percent(conservativeRisk);
    }

    public double intervalWidth() {
        return intervalUpper - intervalLower;
    }

    private static int percent(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value * 100.0)));
    }

    private static void probability(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and in [0, 1]");
        }
    }

    private static void positive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(field + " must be finite and positive");
        }
    }
}
