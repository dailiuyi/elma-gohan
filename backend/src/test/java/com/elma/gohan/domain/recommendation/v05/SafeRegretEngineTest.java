package com.elma.gohan.domain.recommendation.v05;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SafeRegretEngineTest {

    private final SafeRegretEngine engine = new SafeRegretEngine();

    @Test
    void clearlyBestCandidateIsDeterministicForEverySeed() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                2.0, 2, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("best", 0.95, 30, 500, 0.5, 0.0, "A", Set.of("LIGHT")),
                candidate("far", 0.60, 30, 500, 0.5, 0.0, "A", Set.of("LIGHT")));

        for (long seed = 1; seed <= 20; seed++) {
            var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                    config, seed);
            assertThat(decision.pool().get(0).candidate().candidate().candidateId())
                    .isEqualTo("best");
            assertThat(decision.pool().get(0).propensity()).isEqualTo(1.0);
            assertThat(decision.firstChoicePropensities())
                    .containsEntry("best", 1.0)
                    .containsEntry("far", 0.0);
        }
    }

    @Test
    void onlyNearTiesParticipateInSeededSoftmaxAndReplay() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                2.0, 3, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("a", 0.90, 30, 500, 0.5, 0.0, "A", Set.of()),
                candidate("b", 0.89, 30, 500, 0.5, 0.0, "B", Set.of()),
                candidate("outside", 0.60, 30, 500, 0.5, 0.0, "C", Set.of()));

        var first = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 77L);
        var replay = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 77L);

        assertThat(first.pool()).isEqualTo(replay.pool());
        assertThat(first.firstChoicePropensities().get("a")).isPositive();
        assertThat(first.firstChoicePropensities().get("b")).isPositive();
        assertThat(first.firstChoicePropensities()).containsEntry("outside", 0.0);
        assertThat(first.firstChoicePropensities().get("a")
                + first.firstChoicePropensities().get("b")).isCloseTo(1.0, within(1.0e-12));
    }

    @Test
    void uncertainCandidateCannotDisplaceTrustedSafeFirstChoice() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(1, 0, 0, 0, 0),
                2.0, 2, 5.0, 8.0, 0.0);
        SafeRegretCandidate trusted = new SafeRegretCandidate("trusted",
                new SafeRegretCandidate.RiskView(0.20, 0.20, 0.9, true, false),
                0.8, 30, 500, 0.5, 0.0, 0.0, "A", Set.of());
        SafeRegretCandidate uncertainButHigher = new SafeRegretCandidate("uncertain-higher",
                new SafeRegretCandidate.RiskView(0.19, 0.19, 0.9, false, false),
                0.8, 30, 500, 0.5, 0.0, 0.0, "A", Set.of());

        var decision = engine.decide(List.of(trusted, uncertainButHigher),
                new SafeRegretEngine.Request(null, null), config, 78L);

        assertThat(score(decision, "uncertain-higher")).isGreaterThan(score(decision, "trusted"));
        assertThat(decision.pool().get(0).candidate().candidate().candidateId())
                .isEqualTo("trusted");
        assertThat(decision.firstChoicePropensities())
                .containsEntry("trusted", 1.0)
                .containsEntry("uncertain-higher", 0.0);
    }

    @Test
    void allUncertainCandidatesFallbackToLowestConservativeRisk() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                2.0, 2, 5.0, 8.0, 0.0);
        SafeRegretCandidate highScoreHigherRisk = new SafeRegretCandidate("high-score",
                new SafeRegretCandidate.RiskView(0.50, 0.60, 0.8, false, false),
                1.0, 30, 500, 0.5, 0.0, 0.0, "A", Set.of());
        SafeRegretCandidate lowerScoreLowerRisk = new SafeRegretCandidate("lowest-risk",
                new SafeRegretCandidate.RiskView(0.35, 0.41, 0.8, false, false),
                0.1, 30, 500, 0.5, 0.0, 0.0, "B", Set.of());

        var decision = engine.decide(List.of(highScoreHigherRisk, lowerScoreLowerRisk),
                new SafeRegretEngine.Request(null, null), config, 80L);

        assertThat(score(decision, "high-score")).isGreaterThan(score(decision, "lowest-risk"));
        assertThat(decision.pool().get(0).candidate().candidate().candidateId())
                .isEqualTo("lowest-risk");
        assertThat(decision.pool().get(0).reason())
                .isEqualTo("LOWEST_CONSERVATIVE_RISK_FALLBACK");
        assertThat(decision.firstChoicePropensities())
                .containsEntry("lowest-risk", 1.0)
                .containsEntry("high-score", 0.0);
    }

    @Test
    void p90TargetInterpolatesInsteadOfCollapsingToMaximumInSmallPool() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                0.0, 2, 5.0, 8.0, 0.0);
        var decision = engine.decide(List.of(
                        candidate("low", 0.0, 30, 500, 0.5, 0.0, "A", Set.of()),
                        candidate("high", 1.0, 30, 500, 0.5, 0.0, "B", Set.of())),
                new SafeRegretEngine.Request(null, null), config, 81L);

        assertThat(score(decision, "low")).isCloseTo(10.0, within(1.0e-9));
        assertThat(score(decision, "high")).isEqualTo(100.0);
    }

    @Test
    void nearTiePropensityIsZeroOutsideTrustedSafeTier() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                2.0, 3, 5.0, 8.0, 0.0);
        SafeRegretCandidate trustedA = candidate("trusted-a", 0.90, 30, 500,
                0.5, 0.0, "A", Set.of());
        SafeRegretCandidate trustedB = candidate("trusted-b", 0.90, 30, 500,
                0.5, 0.0, "B", Set.of());
        SafeRegretCandidate uncertain = new SafeRegretCandidate("uncertain",
                new SafeRegretCandidate.RiskView(0.1, 0.1, 1.0, false, false),
                0.90, 30, 500, 0.5, 0.0, 0.0, "C", Set.of());

        var decision = engine.decide(List.of(trustedA, trustedB, uncertain),
                new SafeRegretEngine.Request(null, null), config, 79L);

        assertThat(decision.firstChoicePropensities())
                .containsEntry("trusted-a", 0.5)
                .containsEntry("trusted-b", 0.5)
                .containsEntry("uncertain", 0.0);
    }

    @Test
    void returnedPropensityMatchesActualSoftmaxProbability() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                2.0, 2, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("a", 0.90, 30, 500, 0.5, 0.0, "A", Set.of()),
                candidate("b", 0.90, 30, 500, 0.5, 0.0, "B", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 3L);
        String selected = decision.pool().get(0).candidate().candidate().candidateId();

        assertThat(decision.firstChoicePropensities())
                .containsEntry("a", 0.5)
                .containsEntry("b", 0.5);
        assertThat(decision.pool().get(0).propensity())
                .isEqualTo(decision.firstChoicePropensities().get(selected));
    }

    @Test
    void mmrCannotTradeAwayMoreThanConfiguredQualityLoss() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 0, 0, 0),
                0.0, 2, 5.0, 100.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("first", 1.00, 30, 500, 0.5, 0.0, "A", Set.of("SPICY")),
                candidate("similar-good", 0.90, 30, 500, 0.5, 0.0, "A", Set.of("SPICY")),
                candidate("diverse-poor", 0.80, 100, 2000, 0.5, 0.0, "B", Set.of("SWEET")));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 5L);

        assertThat(decision.pool()).extracting(item -> item.candidate().candidate().candidateId())
                .containsExactly("first", "similar-good");
        assertThat(decision.pool().get(1).candidate().score())
                .isGreaterThanOrEqualTo(decision.rankedCandidates().get(1).score() - 5.0);
    }

    @Test
    void multipleMissingFieldsPayOneUnifiedUncertaintyPenalty() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(1, 1, 0, 1, 0),
                0.0, 3, 5.0, 8.0, 0.20);
        SafeRegretCandidate missingQuality = new SafeRegretCandidate("one-missing",
                safeRisk(), null, 30, 500, 0.5, 0.0, 0.0, "A", Set.of());
        SafeRegretCandidate missingQualityAndPrice = new SafeRegretCandidate("two-missing",
                safeRisk(), null, null, 500, 0.5, 0.0, 0.0, "A", Set.of());

        var decision = engine.decide(List.of(missingQuality, missingQualityAndPrice),
                new SafeRegretEngine.Request(20, 40), config, 9L);

        assertThat(decision.rankedCandidates())
                .allSatisfy(item -> {
                    assertThat(item.breakdown().unifiedUncertainty()).isEqualTo(1.0);
                    assertThat(item.breakdown().uncertaintyPenalty()).isEqualTo(0.20);
                });
    }

    @Test
    void tasteWeightGrowsWithProfileConfidence() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 1, 1, 0, 0),
                0.0, 3, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("taste-target", 0.8, 30, 500, 1.0, 1.0, "A", Set.of()),
                candidate("cold-mismatch", 0.8, 30, 500, 0.0, 0.1, "A", Set.of()),
                candidate("warm-mismatch", 0.8, 30, 500, 0.0, 1.0, "A", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 11L);

        assertThat(score(decision, "cold-mismatch"))
                .isGreaterThan(score(decision, "warm-mismatch"));
    }

    @Test
    void zeroTasteConfidenceDoesNotReactivateTasteAsTheOnlyConfiguredWeight() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 0, 1, 0, 0),
                0.0, 2, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("liked", 0.8, 30, 500, 1.0, 0.0, "A", Set.of()),
                candidate("disliked", 0.8, 30, 500, 0.0, 0.0, "A", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 12L);

        assertThat(score(decision, "liked")).isEqualTo(score(decision, "disliked"));
    }

    @Test
    void budgetUsesSatisfactionAndUnknownFallbackInsteadOfCheaperIsBetter() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 0, 0, 1, 0),
                0.0, 4, 5.0, 8.0, 0.05);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("within-low", 0.8, 25, 500, 0.5, 0.0, "A", Set.of()),
                candidate("within-high", 0.8, 45, 500, 0.5, 0.0, "A", Set.of()),
                candidate("unknown", 0.8, null, 500, 0.5, 0.0, "A", Set.of()),
                candidate("outside", 0.8, 80, 500, 0.5, 0.0, "A", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(20, 50),
                config, 13L);

        assertThat(score(decision, "within-low")).isEqualTo(score(decision, "within-high"));
        assertThat(decision.rankedCandidates()).extracting(item -> item.candidate().candidateId())
                .contains("unknown")
                .doesNotContain("outside");
        assertThat(decision.pool().get(0).candidate().breakdown().budgetStatus())
                .isNotEqualTo(SafeRegretDecision.BudgetStatus.UNKNOWN);
    }

    @Test
    void budgetRangeUsesExclusiveMinimumAndInclusiveMaximum() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 0, 0, 1, 0),
                0.0, 5, 5.0, 8.0, 0.05);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("below-minimum", 0.8, 19, 500, 0.5, 0.0, "A", Set.of()),
                candidate("at-minimum", 0.8, 20, 500, 0.5, 0.0, "A", Set.of()),
                candidate("inside", 0.8, 21, 500, 0.5, 0.0, "A", Set.of()),
                candidate("at-maximum", 0.8, 40, 500, 0.5, 0.0, "A", Set.of()),
                candidate("above-maximum", 0.8, 41, 500, 0.5, 0.0, "A", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(20, 40),
                config, 14L);

        assertThat(decision.rankedCandidates())
                .extracting(item -> item.candidate().candidateId())
                .containsExactlyInAnyOrder("inside", "at-maximum");
    }

    @Test
    void distanceUtilityUsesAbsoluteWalkingTime() {
        SafeRegretConfig config = config(new SafeRegretConfig.Weights(0, 0, 0, 0, 1),
                0.0, 2, 5.0, 8.0, 0.0);
        List<SafeRegretCandidate> candidates = List.of(
                candidate("five-minutes", 0.8, 30, 400, 0.5, 0.0, "A", Set.of()),
                candidate("twenty-minutes", 0.8, 30, 1600, 0.5, 0.0, "A", Set.of()));

        var decision = engine.decide(candidates, new SafeRegretEngine.Request(null, null),
                config, 15L);

        assertThat(breakdown(decision, "five-minutes").distanceUtility())
                .isGreaterThan(breakdown(decision, "twenty-minutes").distanceUtility());
        assertThat(decision.pool().get(0).candidate().candidate().candidateId())
                .isEqualTo("five-minutes");
    }

    private SafeRegretConfig config(SafeRegretConfig.Weights weights, double nearTie,
                                    int poolSize, double maxDiversityLoss,
                                    double diversityPenalty, double uncertaintyPenalty) {
        return new SafeRegretConfig(weights, 0.5, uncertaintyPenalty, 0.5,
                80.0, 10.0, nearTie, 1.0, poolSize, maxDiversityLoss,
                diversityPenalty, 30, 500);
    }

    private SafeRegretCandidate candidate(String id, Double quality, Integer price, int distance,
                                          Double taste, double tasteConfidence,
                                          String category, Set<String> flavors) {
        return new SafeRegretCandidate(id, safeRisk(), quality, price, distance, taste,
                tasteConfidence, 0.0, category, flavors);
    }

    private SafeRegretCandidate.RiskView safeRisk() {
        return new SafeRegretCandidate.RiskView(0.1, 0.1, 1.0, true, false);
    }

    private double score(SafeRegretDecision decision, String id) {
        return decision.rankedCandidates().stream()
                .filter(item -> item.candidate().candidateId().equals(id))
                .findFirst().orElseThrow().score();
    }

    private SafeRegretDecision.Breakdown breakdown(SafeRegretDecision decision, String id) {
        return decision.rankedCandidates().stream()
                .filter(item -> item.candidate().candidateId().equals(id))
                .findFirst().orElseThrow().breakdown();
    }
}
