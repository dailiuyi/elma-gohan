package com.elma.gohan.domain.recommendation.v05;

import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.Breakdown;
import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.BudgetStatus;
import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.ScoredCandidate;
import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision.Selection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** recommendation-v0.5 的纯领域评分、近似并列选择和质量受限 MMR。 */
public final class SafeRegretEngine {

    public static final String ALGORITHM_VERSION = "recommendation-v0.5";
    public static final int FEATURE_SCHEMA_VERSION = 2;
    private static final double EPSILON = 1.0e-12;

    /** 一次批量评分和候选池选择；相同输入与 seed 必须得到相同结果。 */
    public SafeRegretDecision decide(
            List<SafeRegretCandidate> candidates,
            Request request,
            SafeRegretConfig config,
            long seed) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        if (config == null) throw new IllegalArgumentException("config 不能为空");

        List<Prepared> prepared = prepare(candidates, request, config);
        if (prepared.isEmpty()) {
            return new SafeRegretDecision(List.of(), List.of(), Map.of(), seed);
        }
        Targets targets = targets(prepared);
        List<ScoredCandidate> ranked = prepared.stream()
                .map(item -> score(item, targets, config))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(item -> item.candidate().candidateId()))
                .toList();

        FirstChoice first = chooseFirst(ranked, config, seed);
        List<Selection> pool = buildPool(ranked, first, config);
        return new SafeRegretDecision(ranked, pool, first.propensities(), seed);
    }

    private List<Prepared> prepare(List<SafeRegretCandidate> candidates, Request request,
                                   SafeRegretConfig config) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        List<Prepared> result = new ArrayList<>();
        for (SafeRegretCandidate candidate : candidates) {
            if (candidate == null || candidate.risk().blocked()) continue;
            if (!ids.add(candidate.candidateId())) {
                throw new IllegalArgumentException("candidateId 不能重复: " + candidate.candidateId());
            }
            BudgetStatus budgetStatus = budgetStatus(candidate.averagePrice(), request);
            if (budgetStatus == null) continue;
            double quality = candidate.qualityUtility() == null
                    ? config.missingUtilityPrior() : candidate.qualityUtility();
            double taste = candidate.tasteUtility() == null
                    ? config.missingUtilityPrior() : candidate.tasteUtility();
            double budget = switch (budgetStatus) {
                case UNCONSTRAINED, SATISFIED -> 1.0;
                case UNKNOWN -> config.missingUtilityPrior();
            };
            double walkingMinutes = candidate.distanceMeters()
                    / config.walkingSpeedMetersPerMinute();
            double distance = Math.pow(0.5,
                    walkingMinutes / config.distanceHalfLifeMinutes());
            double missingUncertainty = candidate.qualityUtility() == null
                    || budgetStatus == BudgetStatus.UNKNOWN ? 1.0 : 0.0;
            double unifiedUncertainty = Math.max(
                    1.0 - candidate.risk().confidence(), missingUncertainty);
            result.add(new Prepared(candidate, budgetStatus,
                    1.0 - candidate.risk().conservativeRisk(), quality, taste, budget,
                    distance, unifiedUncertainty));
        }
        return List.copyOf(result);
    }

    private BudgetStatus budgetStatus(Integer price, Request request) {
        if (!request.budgetConstrained()) return BudgetStatus.UNCONSTRAINED;
        if (price == null) return BudgetStatus.UNKNOWN;
        // 与 SearchCondition/HardFilter 保持一致：下界不包含，上界包含。
        if (request.minBudget() != null && price <= request.minBudget()) return null;
        if (request.maxBudget() != null && price > request.maxBudget()) return null;
        return BudgetStatus.SATISFIED;
    }

    private Targets targets(List<Prepared> prepared) {
        return new Targets(
                percentile90(prepared.stream().map(Prepared::safety).toList()),
                percentile90(prepared.stream().map(Prepared::quality).toList()),
                percentile90(prepared.stream().map(Prepared::taste).toList()),
                percentile90(prepared.stream().map(Prepared::budget).toList()),
                percentile90(prepared.stream().map(Prepared::distance).toList()));
    }

    private ScoredCandidate score(Prepared item, Targets targets, SafeRegretConfig config) {
        SafeRegretConfig.Weights base = config.weights();
        double tasteWeight = base.taste() * item.candidate().tasteConfidence();
        double weightSum = base.safety() + base.quality() + tasteWeight
                + base.budget() + base.distance();
        double safetyWeight;
        double qualityWeight;
        double budgetWeight;
        double distanceWeight;
        if (weightSum <= EPSILON) {
            // 例如只配置 Taste、但冷启动 confidence=0：Taste 不应偷偷恢复为有效权重。
            safetyWeight = 0.0;
            qualityWeight = 0.0;
            tasteWeight = 0.0;
            budgetWeight = 0.0;
            distanceWeight = 0.0;
        } else {
            safetyWeight = base.safety() / weightSum;
            qualityWeight = base.quality() / weightSum;
            tasteWeight /= weightSum;
            budgetWeight = base.budget() / weightSum;
            distanceWeight = base.distance() / weightSum;
        }

        double safetyRegret = regret(targets.safety(), item.safety());
        double qualityRegret = regret(targets.quality(), item.quality());
        double tasteRegret = regret(targets.taste(), item.taste());
        double budgetRegret = regret(targets.budget(), item.budget());
        double distanceRegret = regret(targets.distance(), item.distance());
        double worst = Math.max(safetyWeight * safetyRegret,
                Math.max(qualityWeight * qualityRegret,
                Math.max(tasteWeight * tasteRegret,
                Math.max(budgetWeight * budgetRegret, distanceWeight * distanceRegret))));
        double average = safetyWeight * safetyRegret + qualityWeight * qualityRegret
                + tasteWeight * tasteRegret + budgetWeight * budgetRegret
                + distanceWeight * distanceRegret;
        double uncertaintyPenalty = config.uncertaintyPenalty() * item.unifiedUncertainty();
        double robustRegret = clamp(config.worstRegretBlend() * worst
                + (1.0 - config.worstRegretBlend()) * average
                + uncertaintyPenalty + item.candidate().recentExposurePenalty());
        double score = 100.0 * (1.0 - robustRegret);
        Breakdown breakdown = new Breakdown(item.budgetStatus(), item.safety(), item.quality(),
                item.taste(), item.budget(), item.distance(), worst, average,
                item.unifiedUncertainty(), uncertaintyPenalty,
                item.candidate().recentExposurePenalty(), robustRegret);
        return new ScoredCandidate(item.candidate(), score, breakdown);
    }

    private FirstChoice chooseFirst(List<ScoredCandidate> ranked, SafeRegretConfig config,
                                    long seed) {
        List<ScoredCandidate> preferredBudgetTier = ranked.stream()
                .filter(item -> item.breakdown().budgetStatus() != BudgetStatus.UNKNOWN)
                .toList();
        List<ScoredCandidate> budgetEligible = preferredBudgetTier.isEmpty()
                ? ranked : preferredBudgetTier;
        List<ScoredCandidate> trustedSafeTier = budgetEligible.stream()
                .filter(item -> item.candidate().risk().trustedSafe())
                .toList();
        if (trustedSafeTier.isEmpty()) {
            ScoredCandidate lowestRisk = budgetEligible.stream()
                    .min(Comparator
                            .comparingDouble((ScoredCandidate item) ->
                                    item.candidate().risk().conservativeRisk())
                            .thenComparing(Comparator.comparingDouble(
                                    ScoredCandidate::score).reversed())
                            .thenComparing(item -> item.candidate().candidateId()))
                    .orElseThrow();
            Map<String, Double> probabilities = new LinkedHashMap<>();
            ranked.forEach(item -> probabilities.put(item.candidate().candidateId(), 0.0));
            probabilities.put(lowestRisk.candidate().candidateId(), 1.0);
            return new FirstChoice(lowestRisk, 1.0, Map.copyOf(probabilities),
                    "LOWEST_CONSERVATIVE_RISK_FALLBACK");
        }
        List<ScoredCandidate> firstEligible = trustedSafeTier;
        ScoredCandidate best = firstEligible.get(0);
        List<ScoredCandidate> nearTies = firstEligible.stream()
                .filter(item -> best.score() - item.score() <= config.nearTieScoreDelta() + EPSILON)
                .toList();
        Map<String, Double> probabilities = new LinkedHashMap<>();
        ranked.forEach(item -> probabilities.put(item.candidate().candidateId(), 0.0));
        if (nearTies.size() == 1) {
            probabilities.put(best.candidate().candidateId(), 1.0);
            return new FirstChoice(best, 1.0, Map.copyOf(probabilities),
                    "DETERMINISTIC_BEST");
        }

        List<Double> weights = nearTies.stream()
                .map(item -> Math.exp((item.score() - best.score())
                        / config.softmaxTemperature()))
                .toList();
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        for (int i = 0; i < nearTies.size(); i++) {
            probabilities.put(nearTies.get(i).candidate().candidateId(), weights.get(i) / total);
        }
        double draw = new SplittableRandom(seed).nextDouble();
        double cumulative = 0.0;
        int chosenIndex = nearTies.size() - 1;
        for (int i = 0; i < nearTies.size(); i++) {
            cumulative += weights.get(i) / total;
            if (draw < cumulative) {
                chosenIndex = i;
                break;
            }
        }
        ScoredCandidate chosen = nearTies.get(chosenIndex);
        return new FirstChoice(chosen, weights.get(chosenIndex) / total,
                Map.copyOf(probabilities), "SEEDED_NEAR_TIE_SOFTMAX");
    }

    private List<Selection> buildPool(List<ScoredCandidate> ranked, FirstChoice firstChoice,
                                      SafeRegretConfig config) {
        List<Selection> selected = new ArrayList<>();
        ScoredCandidate first = firstChoice.selected();
        selected.add(new Selection(1, first, firstChoice.propensity(), first.score(),
                firstChoice.reason()));
        List<ScoredCandidate> remaining = new ArrayList<>(ranked);
        remaining.removeIf(item -> item.candidate().candidateId()
                .equals(first.candidate().candidateId()));
        while (selected.size() < config.poolSize() && !remaining.isEmpty()) {
            double bestRemaining = remaining.stream().mapToDouble(ScoredCandidate::score)
                    .max().orElseThrow();
            double floor = bestRemaining - config.maxDiversityLoss();
            ScoredCandidate next = remaining.stream()
                    .filter(item -> item.score() + EPSILON >= floor)
                    .max(Comparator.comparingDouble((ScoredCandidate item) ->
                                    mmrObjective(item, selected, config))
                            .thenComparingDouble(ScoredCandidate::score)
                            .thenComparing(item -> item.candidate().candidateId(),
                                    Comparator.reverseOrder()))
                    .orElseThrow();
            double objective = mmrObjective(next, selected, config);
            selected.add(new Selection(selected.size() + 1, next, 1.0, objective,
                    "QUALITY_GUARDED_MMR"));
            remaining.remove(next);
        }
        return List.copyOf(selected);
    }

    private double mmrObjective(ScoredCandidate candidate, List<Selection> selected,
                                SafeRegretConfig config) {
        double maximumSimilarity = selected.stream()
                .map(Selection::candidate)
                .map(ScoredCandidate::candidate)
                .mapToDouble(existing -> similarity(candidate.candidate(), existing, config))
                .max().orElse(0.0);
        return candidate.score() - config.diversityPenaltyPoints() * maximumSimilarity;
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
        Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new java.util.HashSet<>(a);
        intersection.retainAll(b);
        return intersection.size() / (double) union.size();
    }

    private double neutralEquality(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) return 0.5;
        return a.equalsIgnoreCase(b) ? 1.0 : 0.0;
    }

    private double equality(int a, int b) { return a == b ? 1.0 : 0.0; }

    private double percentile90(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.size() == 1) return sorted.get(0);
        double position = (sorted.size() - 1) * 0.90;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private double regret(double target, double utility) {
        return Math.max(0.0, target - utility);
    }

    private double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    /** 预算约束由现有请求直接映射；已知越界候选不会进入评分。 */
    public record Request(Integer minBudget, Integer maxBudget) {
        public Request {
            if (minBudget != null && minBudget < 0) {
                throw new IllegalArgumentException("minBudget 不能为负数");
            }
            if (maxBudget != null && maxBudget < 0) {
                throw new IllegalArgumentException("maxBudget 不能为负数");
            }
            if (minBudget != null && maxBudget != null && minBudget >= maxBudget) {
                throw new IllegalArgumentException("minBudget 必须小于 maxBudget");
            }
        }
        public boolean budgetConstrained() { return minBudget != null || maxBudget != null; }
    }

    private record Prepared(
            SafeRegretCandidate candidate,
            BudgetStatus budgetStatus,
            double safety,
            double quality,
            double taste,
            double budget,
            double distance,
            double unifiedUncertainty
    ) { }

    private record Targets(
            double safety,
            double quality,
            double taste,
            double budget,
            double distance
    ) { }

    private record FirstChoice(
            ScoredCandidate selected,
            double propensity,
            Map<String, Double> propensities,
            String reason
    ) { }
}
