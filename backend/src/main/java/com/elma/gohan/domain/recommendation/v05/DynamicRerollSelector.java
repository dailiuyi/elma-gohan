package com.elma.gohan.domain.recommendation.v05;

import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.ScoredCandidate;
import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.Selection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 在冻结候选池内，根据本会话已拒绝项确定性选择下一家。 */
public final class DynamicRerollSelector {

    private static final double EPSILON = 1.0e-12;

    /** 使用 decision 的冻结 pool；不会把 pool 外的 ranked candidate 临时补入会话。 */
    public Optional<RerollSelection> selectNext(
            SafeRegretDecision frozenDecision,
            Collection<String> shownCandidateIds,
            List<String> rejectedCandidateIds,
            SafeRegretConfig config) {
        Objects.requireNonNull(frozenDecision, "frozenDecision must not be null");
        List<ScoredCandidate> frozenPool = frozenDecision.pool().stream()
                .map(Selection::candidate)
                .toList();
        return selectNext(frozenPool, shownCandidateIds, rejectedCandidateIds, config);
    }

    /**
     * 使用调用方明确冻结的 pool/ranked 快照。质量下限先于多样性目标生效；稳定 key
     * 在 objective 和原分数均相同时打平。
     */
    public Optional<RerollSelection> selectNext(
            List<ScoredCandidate> frozenCandidates,
            Collection<String> shownCandidateIds,
            List<String> rejectedCandidateIds,
            SafeRegretConfig config) {
        Objects.requireNonNull(frozenCandidates, "frozenCandidates must not be null");
        Objects.requireNonNull(shownCandidateIds, "shownCandidateIds must not be null");
        Objects.requireNonNull(rejectedCandidateIds, "rejectedCandidateIds must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Map<String, ScoredCandidate> frozenById = indexFrozenCandidates(frozenCandidates);
        Set<String> unavailableIds = new HashSet<>(shownCandidateIds);
        List<ScoredCandidate> rejected = new ArrayList<>(rejectedCandidateIds.size());
        for (String rejectedId : rejectedCandidateIds) {
            Objects.requireNonNull(rejectedId, "rejected candidate id must not be null");
            ScoredCandidate candidate = frozenById.get(rejectedId);
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "rejected candidate is not in the frozen set: " + rejectedId);
            }
            rejected.add(candidate);
            unavailableIds.add(rejectedId);
        }

        List<ScoredCandidate> remaining = frozenCandidates.stream()
                .filter(candidate -> !unavailableIds.contains(candidate.candidate().candidateId()))
                .toList();
        if (remaining.isEmpty()) {
            return Optional.empty();
        }

        double bestRemainingScore = remaining.stream()
                .mapToDouble(ScoredCandidate::score)
                .max()
                .orElseThrow();
        double qualityFloor = bestRemainingScore - config.maxDiversityLoss();
        ScoredCandidate lastRejected = rejected.isEmpty() ? null : rejected.get(rejected.size() - 1);

        return remaining.stream()
                .filter(candidate -> candidate.score() + EPSILON >= qualityFloor)
                .map(candidate -> selection(candidate, lastRejected, rejected, qualityFloor, config))
                .max(Comparator.comparingDouble(RerollSelection::objective)
                        .thenComparingDouble(selection -> selection.candidate().score())
                        .thenComparing(selection -> selection.candidate().candidate().candidateId(),
                                Comparator.reverseOrder()));
    }

    private Map<String, ScoredCandidate> indexFrozenCandidates(
            List<ScoredCandidate> frozenCandidates) {
        Map<String, ScoredCandidate> indexed = new LinkedHashMap<>();
        for (ScoredCandidate candidate : frozenCandidates) {
            Objects.requireNonNull(candidate, "frozen candidate must not be null");
            String id = candidate.candidate().candidateId();
            if (indexed.putIfAbsent(id, candidate) != null) {
                throw new IllegalArgumentException("duplicate frozen candidate id: " + id);
            }
        }
        return indexed;
    }

    private RerollSelection selection(
            ScoredCandidate candidate,
            ScoredCandidate lastRejected,
            List<ScoredCandidate> allRejected,
            double qualityFloor,
            SafeRegretConfig config) {
        double lastPenalty = lastRejected == null ? 0.0
                : config.diversityPenaltyPoints()
                * similarity(candidate.candidate(), lastRejected.candidate(), config);
        double maximumRejectedSimilarity = allRejected.stream()
                .mapToDouble(item -> similarity(candidate.candidate(), item.candidate(), config))
                .max()
                .orElse(0.0);
        double allPenalty = config.diversityPenaltyPoints() * maximumRejectedSimilarity;
        double objective = candidate.score() - lastPenalty - allPenalty;
        return new RerollSelection(candidate, objective, qualityFloor, lastPenalty, allPenalty);
    }

    private double similarity(SafeRegretCandidate a, SafeRegretCandidate b,
                              SafeRegretConfig config) {
        double category = neutralEquality(a.categoryKey(), b.categoryKey());
        double flavor = flavorSimilarity(a.flavorTags(), b.flavorTags());
        double price = a.averagePrice() == null || b.averagePrice() == null ? 0.5
                : equality(a.averagePrice() / config.priceBandWidth(),
                b.averagePrice() / config.priceBandWidth());
        double distance = equality(a.distanceMeters() / config.distanceBandWidth(),
                b.distanceMeters() / config.distanceBandWidth());
        return (category + flavor + price + distance) / 4.0;
    }

    private double flavorSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.5;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection.size() / (double) union.size();
    }

    private double neutralEquality(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) return 0.5;
        return a.equalsIgnoreCase(b) ? 1.0 : 0.0;
    }

    private double equality(int a, int b) {
        return a == b ? 1.0 : 0.0;
    }

    /** 可直接写入决策快照的 reroll 选择解释。 */
    public record RerollSelection(
            ScoredCandidate candidate,
            double objective,
            double qualityFloor,
            double lastRejectedSimilarityPenalty,
            double allRejectedSimilarityPenalty) {
    }
}
