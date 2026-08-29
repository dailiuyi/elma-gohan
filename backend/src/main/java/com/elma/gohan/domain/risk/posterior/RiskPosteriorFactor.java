package com.elma.gohan.domain.risk.posterior;

import java.util.Objects;

/**
 * One risk factor prepared for posterior aggregation.
 *
 * <p>{@code riskIntensity} is h in [0, 1], {@code reliability} is q in [0, 1],
 * and {@code weight} is the factor's planned share of the evidence budget. A
 * missing factor has no h and q=0: absence therefore contributes neither risky
 * nor safe pseudo-counts.</p>
 */
public record RiskPosteriorFactor(
        String key,
        EvidenceState evidenceState,
        Double riskIntensity,
        double reliability,
        double weight
) {

    public enum EvidenceState {
        OBSERVED,
        NO_DATA,
        UNAVAILABLE
    }

    public RiskPosteriorFactor {
        key = Objects.requireNonNull(key, "key").trim();
        if (key.isEmpty()) throw new IllegalArgumentException("key must not be blank");
        evidenceState = Objects.requireNonNull(evidenceState, "evidenceState");
        requireFiniteInRange(weight, 0.0, 1.0, "weight");
        if (weight == 0.0) throw new IllegalArgumentException("weight must be greater than zero");

        if (evidenceState == EvidenceState.OBSERVED) {
            if (riskIntensity == null) {
                throw new IllegalArgumentException("observed factor requires riskIntensity");
            }
            requireFiniteInRange(riskIntensity, 0.0, 1.0, "riskIntensity");
            requireFiniteInRange(reliability, 0.0, 1.0, "reliability");
        } else {
            if (riskIntensity != null) {
                throw new IllegalArgumentException("missing factor must not declare riskIntensity");
            }
            if (Double.compare(reliability, 0.0) != 0) {
                throw new IllegalArgumentException("missing factor reliability must be zero");
            }
        }
    }

    public static RiskPosteriorFactor observed(String key, double riskIntensity,
                                                double reliability, double weight) {
        return new RiskPosteriorFactor(key, EvidenceState.OBSERVED,
                riskIntensity, reliability, weight);
    }

    public static RiskPosteriorFactor noData(String key, double weight) {
        return new RiskPosteriorFactor(key, EvidenceState.NO_DATA, null, 0.0, weight);
    }

    public static RiskPosteriorFactor unavailable(String key, double weight) {
        return new RiskPosteriorFactor(key, EvidenceState.UNAVAILABLE, null, 0.0, weight);
    }

    public boolean observed() {
        return evidenceState == EvidenceState.OBSERVED;
    }

    private static void requireFiniteInRange(double value, double minimum, double maximum,
                                             String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be finite and in ["
                    + minimum + ", " + maximum + "]");
        }
    }
}
