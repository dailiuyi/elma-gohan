package com.elma.gohan.domain.recommendation.v05;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DynamicRerollSelectorTest {

    private final SafeRegretEngine engine = new SafeRegretEngine();
    private final DynamicRerollSelector selector = new DynamicRerollSelector();

    @Test
    void choosesCandidateMoreDifferentFromLastRejectedWhenQualityIsClose() {
        SafeRegretConfig config = config(3, 5.0, 10.0);
        SafeRegretDecision decision = decision(config,
                candidate("rejected", 1.00, 20, 400, "A", Set.of("SPICY")),
                candidate("similar", 0.99, 20, 400, "A", Set.of("SPICY")),
                candidate("diverse", 0.98, 100, 1800, "B", Set.of("SWEET")));

        var next = selector.selectNext(decision, Set.of("rejected"),
                List.of("rejected"), config);

        assertThat(next).isPresent();
        assertThat(next.orElseThrow().candidate().candidate().candidateId()).isEqualTo("diverse");
        assertThat(next.orElseThrow().lastRejectedSimilarityPenalty()).isZero();
    }

    @Test
    void diversityCannotBreakConfiguredQualityFloor() {
        SafeRegretConfig config = config(3, 5.0, 100.0);
        SafeRegretDecision decision = decision(config,
                candidate("rejected", 1.00, 20, 400, "A", Set.of("SPICY")),
                candidate("similar-good", 0.95, 20, 400, "A", Set.of("SPICY")),
                candidate("diverse-poor", 0.80, 100, 1800, "B", Set.of("SWEET")));

        var next = selector.selectNext(decision, Set.of("rejected"),
                List.of("rejected"), config).orElseThrow();

        assertThat(next.candidate().candidate().candidateId()).isEqualTo("similar-good");
        assertThat(next.candidate().score()).isGreaterThanOrEqualTo(next.qualityFloor());
    }

    @Test
    void alreadyShownCandidateIsNeverSelectedAgain() {
        SafeRegretConfig config = config(3, 5.0, 10.0);
        SafeRegretDecision decision = decision(config,
                candidate("rejected", 1.00, 20, 400, "A", Set.of("SPICY")),
                candidate("already-shown", 0.99, 100, 1800, "B", Set.of("SWEET")),
                candidate("unseen", 0.98, 100, 1800, "B", Set.of("SWEET")));

        var next = selector.selectNext(decision, Set.of("rejected", "already-shown"),
                List.of("rejected"), config).orElseThrow();

        assertThat(next.candidate().candidate().candidateId()).isEqualTo("unseen");
    }

    @Test
    void exhaustedFrozenPoolReturnsExplicitEmpty() {
        SafeRegretConfig config = config(2, 5.0, 10.0);
        SafeRegretDecision decision = decision(config,
                candidate("first", 1.00, 20, 400, "A", Set.of("SPICY")),
                candidate("second", 0.99, 100, 1800, "B", Set.of("SWEET")));

        assertThat(selector.selectNext(decision, Set.of("first", "second"),
                List.of("first", "second"), config)).isEmpty();
    }

    @Test
    void stableCandidateIdBreaksAnExactObjectiveTie() {
        SafeRegretConfig config = config(3, 5.0, 10.0);
        SafeRegretDecision decision = decision(config,
                candidate("rejected", 1.00, 20, 400, "A", Set.of("SPICY")),
                candidate("b", 0.95, 100, 1800, "B", Set.of("SWEET")),
                candidate("a", 0.95, 100, 1800, "B", Set.of("SWEET")));

        var first = selector.selectNext(decision, Set.of("rejected"),
                List.of("rejected"), config).orElseThrow();
        var replay = selector.selectNext(decision, Set.of("rejected"),
                List.of("rejected"), config).orElseThrow();

        assertThat(first).isEqualTo(replay);
        assertThat(first.candidate().candidate().candidateId()).isEqualTo("a");
    }

    private SafeRegretDecision decision(SafeRegretConfig config,
                                         SafeRegretCandidate... candidates) {
        return engine.decide(List.of(candidates),
                new SafeRegretEngine.Request(null, null), config, 17L);
    }

    private SafeRegretConfig config(int poolSize, double maxDiversityLoss,
                                    double diversityPenaltyPoints) {
        return new SafeRegretConfig(
                new SafeRegretConfig.Weights(0.0, 1.0, 0.0, 0.0, 0.0),
                0.5, 0.0, 0.5, 80.0, 10.0, 0.0, 1.0,
                poolSize, maxDiversityLoss, diversityPenaltyPoints, 30, 500);
    }

    private SafeRegretCandidate candidate(String id, double quality, int price, int distance,
                                          String category, Set<String> flavors) {
        return new SafeRegretCandidate(id,
                new SafeRegretCandidate.RiskView(0.10, 0.10, 1.0, true, false),
                quality, price, distance, 0.5, 0.0, 0.0, category, flavors);
    }
}
