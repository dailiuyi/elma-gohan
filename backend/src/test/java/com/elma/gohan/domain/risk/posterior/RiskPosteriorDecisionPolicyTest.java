package com.elma.gohan.domain.risk.posterior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RiskPosteriorDecisionPolicyTest {

    private final RiskPosteriorDecisionPolicy policy =
            new RiskPosteriorDecisionPolicy(0.75, 0.35, 0.60);

    @Test
    void pureUnknownIsAlwaysUncertainEvenWithHighConservativeRisk() {
        RiskPosteriorResult unknown = result(0.0, 0.95);

        assertThat(policy.classify(unknown))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.UNCERTAIN);
        assertThat(policy.isUncertain(unknown)).isTrue();
        assertThat(policy.isBlocked(unknown)).isFalse();
        assertThat(policy.isTrustedSafe(unknown)).isFalse();
    }

    @Test
    void sufficientlyTrustedLowRiskIsTrustedSafe() {
        RiskPosteriorResult low = result(0.80, 0.25);

        assertThat(policy.classify(low))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.TRUSTED_SAFE);
        assertThat(policy.isTrustedSafe(low)).isTrue();
        assertThat(policy.classify(low).trustedSafe()).isTrue();
    }

    @Test
    void sufficientlyTrustedHighRiskIsBlocked() {
        RiskPosteriorResult high = result(0.80, 0.85);

        assertThat(policy.classify(high))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.BLOCKED);
        assertThat(policy.isBlocked(high)).isTrue();
        assertThat(policy.classify(high).blocked()).isTrue();
    }

    @Test
    void trustedResultBetweenThresholdsRemainsUncertain() {
        RiskPosteriorResult middle = result(0.80, 0.55);

        assertThat(policy.classify(middle))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.UNCERTAIN);
        assertThat(policy.classify(middle).uncertain()).isTrue();
    }

    @Test
    void overlappingOrReversedThresholdsAreRejected() {
        assertThatThrownBy(() -> new RiskPosteriorDecisionPolicy(0.60, 0.60, 0.50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be lower");
        assertThatThrownBy(() -> new RiskPosteriorDecisionPolicy(0.60, 0.70, 0.50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be lower");
    }

    @Test
    void defaultConfidenceGateSeparatesPriorOnlyFromCalibratedBoundaryPosterior() {
        RiskPosteriorCalculator calculator = new RiskPosteriorCalculator(
                RiskPosteriorConfig.defaults());
        RiskPosteriorResult priorOnly = calculator.calculate(RiskPosteriorInput.of(
                RiskPosteriorFactor.noData("unknown", 1.0)));
        RiskPosteriorResult boundary = calculator.calculate(RiskPosteriorInput.of(
                RiskPosteriorFactor.observed("boundary", 0.61, 0.25, 1.0)));
        RiskPosteriorDecisionPolicy defaults = RiskPosteriorDecisionPolicy.defaults();

        assertThat(defaults.minimumConfidence()).isEqualTo(0.75);
        assertThat(priorOnly.confidence()).isLessThan(defaults.minimumConfidence());
        assertThat(boundary.confidence()).isGreaterThan(defaults.minimumConfidence());
        assertThat(defaults.classify(priorOnly))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.UNCERTAIN);
        assertThat(defaults.classify(boundary))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.BLOCKED);
    }

    private RiskPosteriorResult result(double confidence, double conservativeRisk) {
        double mean = Math.min(0.40, conservativeRisk);
        return new RiskPosteriorResult("risk-v0.5", 4.0, 6.0,
                mean, confidence, Math.min(0.20, mean), conservativeRisk, conservativeRisk,
                2.0, confidence > 0 ? 1 : 0, confidence > 0 ? 0 : 1);
    }
}
