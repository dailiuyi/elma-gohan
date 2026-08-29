package com.elma.gohan.domain.risk.posterior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiskPosteriorCalculatorTest {

    private final RiskPosteriorConfig config = RiskPosteriorConfig.defaults();
    private final RiskPosteriorCalculator calculator = new RiskPosteriorCalculator(config);

    @Test
    void higherRiskIntensityMonotonicallyRaisesPosteriorAndConservativeRisk() {
        RiskPosteriorResult lower = calculateObserved(0.20, 0.80);
        RiskPosteriorResult higher = calculateObserved(0.80, 0.80);

        assertThat(higher.posteriorMean()).isGreaterThan(lower.posteriorMean());
        assertThat(higher.conservativeRisk()).isGreaterThan(lower.conservativeRisk());
    }

    @Test
    void higherReliabilityNarrowsIntervalAndMovesConservativeRiskTowardStrongHighSignal() {
        RiskPosteriorResult weak = calculateObserved(0.80, 0.25);
        RiskPosteriorResult strong = calculateObserved(0.80, 1.00);

        assertThat(strong.confidence()).isGreaterThan(weak.confidence());
        assertThat(strong.intervalWidth()).isLessThan(weak.intervalWidth());
        assertThat(strong.posteriorMean()).isGreaterThan(weak.posteriorMean());
        assertThat(strong.conservativeRisk()).isGreaterThan(weak.conservativeRisk());
    }

    @Test
    void confidenceIsOneMinusCredibleIntervalWidthOnProbabilitySupport() {
        RiskPosteriorResult result = calculateObserved(0.65, 0.80);

        assertThat(result.confidence())
                .isCloseTo(1.0 - result.intervalWidth(), within(1.0e-12));
    }

    @Test
    void equalEvidenceMassCanHaveDifferentIntervalWidthAndConfidence() {
        RiskPosteriorResult lowHarm = calculateObserved(0.20, 0.80);
        RiskPosteriorResult highHarm = calculateObserved(0.80, 0.80);

        assertThat(highHarm.effectiveEvidenceMass())
                .isEqualTo(lowHarm.effectiveEvidenceMass());
        assertThat(highHarm.intervalWidth()).isNotEqualTo(lowHarm.intervalWidth());
        assertThat(highHarm.confidence()).isNotEqualTo(lowHarm.confidence());
    }

    @Test
    void noDataStaysAtUncertainPriorInsteadOfBecomingExplicitLowRisk() {
        RiskPosteriorResult noData = calculator.calculate(RiskPosteriorInput.of(
                RiskPosteriorFactor.noData("reviews", 1.0)));
        RiskPosteriorResult explicitLow = calculateObserved(0.0, 1.0);

        assertThat(noData.posteriorMean()).isEqualTo(config.priorRiskMean());
        assertThat(noData.confidence())
                .isCloseTo(1.0 - noData.intervalWidth(), within(1.0e-12));
        assertThat(noData.confidence()).isCloseTo(0.70742, within(1.0e-5));
        assertThat(noData.confidence())
                .isLessThan(RiskPosteriorDecisionPolicy.defaults().minimumConfidence());
        assertThat(noData.posteriorRiskScore()).isGreaterThan(20);
        assertThat(noData.conservativeRiskScore()).isGreaterThan(20);
        assertThat(noData.posteriorMean()).isGreaterThan(explicitLow.posteriorMean());
        assertThat(noData.missingFactorCount()).isEqualTo(1);
    }

    @Test
    void replayIsDeterministicAndIndependentOfCallerFactorOrder() {
        RiskPosteriorFactor rating = RiskPosteriorFactor.observed("rating", 0.65, 0.90, 0.45);
        RiskPosteriorFactor trend = RiskPosteriorFactor.observed("trend", 0.30, 0.70, 0.30);
        RiskPosteriorFactor reviews = RiskPosteriorFactor.noData("reviews", 0.25);

        RiskPosteriorResult first = calculator.calculate(new RiskPosteriorInput(
                List.of(rating, trend, reviews)));
        RiskPosteriorResult replay = calculator.calculate(new RiskPosteriorInput(
                List.of(reviews, rating, trend)));

        assertThat(replay).isEqualTo(first);
        assertThat(first.algorithmVersion()).isEqualTo("risk-v0.5");
    }

    @Test
    void conservativeRiskIsExactBetaQ80AndCrossesHighPolicyBoundary() {
        RiskPosteriorResult result = calculator.calculate(RiskPosteriorInput.of(
                RiskPosteriorFactor.observed("boundary", 0.61, 0.25, 1.0)));
        RiskPosteriorDecisionPolicy policy = RiskPosteriorDecisionPolicy.defaults();

        assertThat(result.posteriorAlpha()).isCloseTo(8.08, within(1.0e-12));
        assertThat(result.posteriorBeta()).isCloseTo(7.92, within(1.0e-12));
        assertThat(result.conservativeRisk()).isCloseTo(0.61069, within(1.0e-5));
        assertThat(result.confidence()).isCloseTo(0.7887, within(1.0e-4));
        assertThat(result.confidence()).isGreaterThan(policy.minimumConfidence());
        assertThat(policy.classify(result))
                .isEqualTo(RiskPosteriorDecisionPolicy.DecisionTier.BLOCKED);
    }

    private RiskPosteriorResult calculateObserved(double riskIntensity, double reliability) {
        return calculator.calculate(RiskPosteriorInput.of(
                RiskPosteriorFactor.observed("factor", riskIntensity, reliability, 1.0)));
    }
}
