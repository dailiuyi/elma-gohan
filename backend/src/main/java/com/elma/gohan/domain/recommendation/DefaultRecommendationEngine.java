package com.elma.gohan.domain.recommendation;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.domain.restaurant.CategoryFilter;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** recommendation-v0.4.1：V0.4 个性化排序加可重放的探索与候选池抽取。 */
@Component
public class DefaultRecommendationEngine implements RecommendationEngine {
    private record Scored(Restaurant restaurant, RiskResult risk, PersonalizedScore score) { }
    private record SelectionPlan(List<SelectionCandidate> selected, String explorationKey) { }
    private final HardFilter hardFilter;
    private final LowRegretScorer scorer;
    private final RecommendationProperties props;

    public DefaultRecommendationEngine(HardFilter hardFilter, LowRegretScorer scorer,
            RecommendationProperties props) {
        this.hardFilter = hardFilter;
        this.scorer = scorer;
        this.props = props;
    }

    /** 过滤高风险候选后完成评分、探索和多样化抽取。 */
    @Override
    public RecommendationResult recommend(List<Restaurant> candidates,
            Map<String, RiskResult> risks, UserPreference preference, long seed) {
        SearchCondition condition = preference.condition();
        List<Restaurant> filtered = hardFilter.filter(candidates, condition).stream()
                .filter(r -> risks.get(r.sourcePoiId()) != null)
                .filter(r -> !isBlocked(risks.get(r.sourcePoiId())))
                .toList();
        if (filtered.isEmpty()) {
            return new RecommendationResult(List.of(), props.getAlgorithmVersion(), seed, List.of());
        }

        List<Scored> scored = filtered.stream()
                .map(r -> new Scored(r, risks.get(r.sourcePoiId()),
                        scorer.details(r, risks.get(r.sourcePoiId()), preference)))
                .sorted(Comparator.comparingDouble((Scored s) -> s.score().score()).reversed()
                        .thenComparing(s -> stableKey(s.restaurant())))
                .toList();
        int topKSize = Math.min(props.getTopK(), scored.size());
        List<SelectionCandidate> selectionSnapshot = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Scored item = scored.get(i);
            selectionSnapshot.add(new SelectionCandidate(item.restaurant().source(),
                    item.restaurant().sourcePoiId(), diversityKey(item.restaurant(), condition),
                    item.score().score(), i < topKSize, explorationEligible(item, preference)));
        }
        int poolSize = Math.min(props.getPoolSize(), scored.size());
        SelectionPlan plan = planSelection(selectionSnapshot, poolSize, seed);
        Map<String, Scored> scoredByKey = scored.stream().collect(Collectors.toMap(
                item -> stableKey(item.restaurant()), item -> item));
        List<Scored> picked = plan.selected().stream()
                .map(item -> scoredByKey.get(item.candidateKey()))
                .filter(java.util.Objects::nonNull)
                .map(item -> itemKey(item).equals(plan.explorationKey())
                        ? new Scored(item.restaurant(), item.risk(), scorer.withExploration(item.score()))
                        : item)
                .toList();

        SelectionMode normalMode = !picked.isEmpty() && picked.get(0).score().confidence() > 0
                ? SelectionMode.PERSONALIZED : SelectionMode.DEFAULT;
        List<RestaurantCandidate> pool = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Scored item = picked.get(i);
            SelectionMode mode = i == 0 && itemKey(item).equals(plan.explorationKey())
                    ? SelectionMode.EXPLORATION : normalMode;
            PersonalizationSnapshot snapshot = new PersonalizationSnapshot(
                    item.score().tasteMatchScore(), item.score().confidence(), mode,
                    item.score().personalizationReasons(), "taste-v0.1", item.score().breakdown());
            pool.add(new RestaurantCandidate(item.restaurant(), item.risk(), item.score().score(),
                    item.score().reasons(), snapshot));
        }
        return new RecommendationResult(List.copyOf(pool), props.getAlgorithmVersion(), seed,
                List.copyOf(selectionSnapshot));
    }

    private boolean explorationEligible(Scored scored, UserPreference preference) {
        Restaurant r = scored.restaurant();
        return scored.risk().riskLevel() == RiskLevel.LOW
                && scored.risk().confidence() >= props.getExplorationMinimumConfidence()
                && !preference.recentHistory().ateCategoryWithinDays(r.categoryCode(), 7)
                && !preference.recentHistory().dislikedCategoryWithinDays(r.categoryCode(), 30)
                && preference.tasteProfile().categoryWeight(r)
                    >= props.getExplorationNegativePreferenceThreshold();
    }

    /** 使用冻结快照和随机种子重放候选池选择。 */
    @Override
    public List<SelectionCandidate> replaySelection(List<SelectionCandidate> selectionSnapshot,
                                                     int poolSize, long seed) {
        return planSelection(selectionSnapshot, poolSize, seed).selected();
    }

    private SelectionPlan planSelection(List<SelectionCandidate> selectionSnapshot,
                                        int poolSize, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        SelectionCandidate explorer = null;
        if (random.nextDouble() < props.getExplorationRate()) {
            explorer = selectionSnapshot.stream()
                    .filter(SelectionCandidate::explorationEligible)
                    .findFirst().orElse(null);
        }
        List<SelectionCandidate> topK = selectionSnapshot.stream()
                .filter(SelectionCandidate::topK)
                .collect(Collectors.toCollection(ArrayList::new));
        List<SelectionCandidate> picked = new ArrayList<>();
        if (explorer != null) {
            picked.add(explorer);
            String explorerKey = explorer.candidateKey();
            topK.removeIf(item -> item.candidateKey().equals(explorerKey));
        }

        Map<String, List<SelectionCandidate>> groups = new LinkedHashMap<>();
        topK.forEach(item -> groups.computeIfAbsent(item.diversityKey(),
                ignored -> new ArrayList<>()).add(item));
        WeightedRandomSelector selector = new WeightedRandomSelector(random.nextLong());
        while (picked.size() < poolSize) {
            boolean added = false;
            for (List<SelectionCandidate> group : groups.values()) {
                if (picked.size() >= poolSize || group.isEmpty()) continue;
                List<Double> weights = group.stream()
                        .map(item -> Math.max(1.0, item.lowRegretScore())).toList();
                SelectionCandidate chosen = selector.select(group, weights, 1).get(0);
                group.remove(chosen); picked.add(chosen); added = true;
            }
            if (!added) break;
        }
        String explorerKey = explorer == null ? null : explorer.candidateKey();
        return new SelectionPlan(List.copyOf(picked), explorerKey);
    }
    private String diversityKey(Restaurant r, SearchCondition c) {
        if (c.categoryUnlimited()) return CategoryFilter.groupCodeForRestaurant(r.categoryCode());
        return r.categoryCode() == null ? "OTHER" : r.categoryCode().toUpperCase(Locale.ROOT);
    }
    private String stableKey(Restaurant restaurant) {
        return (restaurant.source() == null ? "" : restaurant.source()) + "\u0000"
                + (restaurant.sourcePoiId() == null ? "" : restaurant.sourcePoiId());
    }

    private String itemKey(Scored item) {
        return stableKey(item.restaurant());
    }
}
