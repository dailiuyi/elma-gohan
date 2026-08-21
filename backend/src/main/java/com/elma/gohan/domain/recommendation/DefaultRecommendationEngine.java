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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** recommendation-v0.4：客观风险过滤后再做个性化、近期历史与低风险探索。 */
@Component
public class DefaultRecommendationEngine implements RecommendationEngine {
    private record Scored(Restaurant restaurant, RiskResult risk, PersonalizedScore score) { }
    private final HardFilter hardFilter;
    private final LowRegretScorer scorer;
    private final RecommendationProperties props;
    private final DoubleSupplier random;

    @Autowired
    public DefaultRecommendationEngine(HardFilter hardFilter, LowRegretScorer scorer,
            RecommendationProperties props) {
        this(hardFilter, scorer, props, () -> ThreadLocalRandom.current().nextDouble());
    }
    public DefaultRecommendationEngine(HardFilter hardFilter, LowRegretScorer scorer,
            RecommendationProperties props, DoubleSupplier random) {
        this.hardFilter = hardFilter; this.scorer = scorer; this.props = props; this.random = random;
    }

    @Override
    public RecommendationResult recommend(List<Restaurant> candidates,
            Map<String, RiskResult> risks, UserPreference preference) {
        SearchCondition condition = preference.condition();
        List<Restaurant> filtered = hardFilter.filter(candidates, condition).stream()
                .filter(r -> risks.get(r.sourcePoiId()) != null)
                .filter(r -> !isBlocked(risks.get(r.sourcePoiId())))
                .toList();
        if (filtered.isEmpty()) return new RecommendationResult(List.of(), props.getAlgorithmVersion());

        List<Scored> scored = filtered.stream()
                .map(r -> new Scored(r, risks.get(r.sourcePoiId()),
                        scorer.details(r, risks.get(r.sourcePoiId()), preference)))
                .sorted(Comparator.comparingDouble((Scored s) -> s.score().score()).reversed())
                .toList();
        List<Scored> diversified = diversify(scored, condition);
        List<Scored> topK = new ArrayList<>(diversified.subList(0,
                Math.min(props.getTopK(), diversified.size())));
        int poolSize = Math.min(props.getPoolSize(), Math.max(topK.size(), 1));

        Scored explorer = null;
        if (random.getAsDouble() < props.getExplorationRate()) {
            explorer = scored.stream().filter(s -> explorationEligible(s, preference)).findFirst().orElse(null);
        }
        List<Scored> picked = new ArrayList<>();
        if (explorer != null) {
            picked.add(new Scored(explorer.restaurant(), explorer.risk(), scorer.withExploration(explorer.score())));
            Restaurant chosen = explorer.restaurant();
            topK.removeIf(s -> sameRestaurant(s.restaurant(), chosen));
        }
        picked.addAll(selectDiversified(topK, condition, poolSize - picked.size()));

        SelectionMode normalMode = !picked.isEmpty() && picked.get(0).score().confidence() > 0
                ? SelectionMode.PERSONALIZED : SelectionMode.DEFAULT;
        List<RestaurantCandidate> pool = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Scored item = picked.get(i);
            SelectionMode mode = i == 0 && explorer != null ? SelectionMode.EXPLORATION : normalMode;
            PersonalizationSnapshot snapshot = new PersonalizationSnapshot(
                    item.score().tasteMatchScore(), item.score().confidence(), mode,
                    item.score().personalizationReasons(), "taste-v0.1", item.score().breakdown());
            pool.add(new RestaurantCandidate(item.restaurant(), item.risk(), item.score().score(),
                    item.score().reasons(), snapshot));
        }
        return new RecommendationResult(List.copyOf(pool), props.getAlgorithmVersion());
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

    private List<Scored> selectDiversified(List<Scored> topK, SearchCondition condition, int count) {
        Map<String, List<Scored>> groups = new LinkedHashMap<>();
        topK.forEach(item -> groups.computeIfAbsent(diversityKey(item.restaurant(), condition),
                ignored -> new ArrayList<>()).add(item));
        WeightedRandomSelector selector = new WeightedRandomSelector(Double.doubleToLongBits(random.getAsDouble()));
        List<Scored> picked = new ArrayList<>();
        while (picked.size() < count) {
            boolean added = false;
            for (List<Scored> group : groups.values()) {
                if (picked.size() >= count || group.isEmpty()) continue;
                List<Double> weights = group.stream().map(item -> Math.max(1.0, item.score().score())).toList();
                Scored chosen = selector.select(group, weights, 1).get(0);
                group.remove(chosen); picked.add(chosen); added = true;
            }
            if (!added) break;
        }
        return picked;
    }

    private List<Scored> diversify(List<Scored> ranked, SearchCondition condition) {
        Map<String, List<Scored>> groups = new LinkedHashMap<>();
        ranked.forEach(item -> groups.computeIfAbsent(diversityKey(item.restaurant(), condition),
                ignored -> new ArrayList<>()).add(item));
        List<Scored> result = new ArrayList<>(ranked.size());
        for (int depth = 0; result.size() < ranked.size(); depth++) {
            for (List<Scored> group : groups.values()) if (depth < group.size()) result.add(group.get(depth));
        }
        return result;
    }
    private String diversityKey(Restaurant r, SearchCondition c) {
        if (c.categoryUnlimited()) return CategoryFilter.groupCodeForRestaurant(r.categoryCode());
        return r.categoryCode() == null ? "OTHER" : r.categoryCode().toUpperCase(Locale.ROOT);
    }
    private boolean sameRestaurant(Restaurant a, Restaurant b) {
        return a.source().equals(b.source()) && a.sourcePoiId().equals(b.sourcePoiId());
    }
}
