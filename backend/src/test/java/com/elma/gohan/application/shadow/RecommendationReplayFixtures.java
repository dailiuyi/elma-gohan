package com.elma.gohan.application.shadow;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.*;
import com.elma.gohan.domain.restaurant.*;
import com.elma.gohan.domain.risk.*;
import com.elma.gohan.provider.evidence.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

/** 人工构造的边界样本；不代表真实门店分布或用户接受率。 */
final class RecommendationReplayFixtures {
    static final LocalDateTime AS_OF = LocalDateTime.of(2026, 10, 22, 12, 0);
    static final Instant INSTANT = AS_OF.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    static final long[] SEEDS = {2, 7, 42, 77, 97, 123, 987654, Long.MAX_VALUE};

    record Scenario(String name, List<Restaurant> restaurants, UserPreference preference,
                    Map<String, EvidenceBundle> evidence, String excludePoiId) {
        List<Restaurant> hardEligible() {
            return new HardFilter().filter(restaurants, preference.condition());
        }

        Map<String, RiskResult> risks() {
            RiskProperties config = new RiskProperties();
            var engine = new RuleBasedRiskEngine(config, new JaccardTemplateCommentDetector(config),
                    new SlidingWindowBurstDetector(config), new RuleBasedRecentTrendDetector(config),
                    Clock.fixed(INSTANT, ZoneOffset.UTC));
            Map<String, RiskResult> result = new LinkedHashMap<>();
            for (Restaurant r : hardEligible()) result.put(r.sourcePoiId(), engine.evaluate(r, evidence.get(r.sourcePoiId())));
            return Map.copyOf(result);
        }

        List<Restaurant> eligible() {
            return hardEligible().stream().filter(r -> !r.sourcePoiId().equals(excludePoiId)).toList();
        }

        RecommendationResult served(long seed) {
            var config = new RecommendationProperties();
            return new DefaultRecommendationEngine(new LowRegretScorer(config), config,
                    new TasteProperties()).recommend(eligible(), risks(), preference, seed);
        }

        SafeRegretShadowInput input(long seed) {
            return new SafeRegretShadowInput(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    UUID.nameUUIDFromBytes((name + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    hardEligible(), eligible(), evidence, risks(), preference, served(seed), seed, AS_OF);
        }
    }

    static List<Scenario> scenarios() {
        List<Restaurant> base = IntStream.range(0, 12).mapToObj(i -> restaurant("p" + i,
                List.of("CHINESE", "SNACK", "COFFEE").get(i % 3),
                4.8 - i * .07, 200 + i * 100, 20 + i * 5,
                DataCompleteness.FULL, CategoryConfidence.VERIFIED)).toList();
        SearchCondition all = new SearchCondition(2000, null, "ANY", List.of());
        var empty = preference(all, TasteProfile.empty(AS_OF), List.of());
        List<Scenario> result = new ArrayList<>();
        result.add(scenario("cold-start-top-k", base, empty, "MATCHED", false, null));
        result.add(scenario("single-source", base, empty, "NO_MATCH", false, null));
        result.add(scenario("baidu-rate-limited", base, empty, "UNAVAILABLE", false, null));
        result.add(scenario("stale-evidence", base, empty, "MATCHED", true, null));
        result.add(scenario("exclude-after-risk", base, empty, "MATCHED", false, "p0"));
        List<Restaurant> missing = List.of(
                restaurant("missing", "CHINESE", null, 300, null, DataCompleteness.MINIMAL, CategoryConfidence.INFERRED),
                restaurant("known", "CHINESE", 4.6, 500, 35, DataCompleteness.FULL, CategoryConfidence.VERIFIED));
        result.add(scenario("missing-fields", missing, empty, "NO_MATCH", false, null));
        result.add(scenario("sparse-pool", base.subList(0, 1), empty, "MATCHED", false, null));
        result.add(scenario("budget-distance-boundaries", base,
                preference(new SearchCondition(300, 1000, 30, 60, "ANY", List.of()),
                        TasteProfile.empty(AS_OF), List.of()), "MATCHED", false, null));
        result.add(scenario("dislikes-category", base,
                preference(new SearchCondition(2000, null, "CHINESE", List.of("p3")),
                        TasteProfile.empty(AS_OF), List.of()), "MATCHED", false, null));
        List<RecentFoodHistory.Entry> history = List.of(
                new RecentFoodHistory.Entry("amap", "p0", "CHINESE", "DISLIKE", AS_OF.minusDays(7)),
                new RecentFoodHistory.Entry("AMAP", "other", "SNACK", "LIKE", AS_OF.minusDays(1)),
                new RecentFoodHistory.Entry("AMAP", "other2", "SNACK", "NORMAL", AS_OF.minusDays(2)),
                new RecentFoodHistory.Entry("AMAP", "old", "COFFEE", "DISLIKE", AS_OF.minusDays(30)));
        result.add(scenario("recent-history-boundaries", base,
                preference(all, TasteProfile.empty(AS_OF), history), "MATCHED", false, null));
        TasteProfile profile = TasteProfile.empty(AS_OF);
        for (int i = 0; i < 5; i++) profile = profile.applyFeedback(base.get(0), 200, "LIKE",
                List.of(FlavorTag.SPICY), AS_OF, new TasteProperties());
        result.add(scenario("personalized", base, preference(all, profile, history), "MATCHED", false, null));
        List<Restaurant> ties = IntStream.range(0, 8).mapToObj(i -> restaurant("tie" + i, "CHINESE",
                4.5, 300, 35, DataCompleteness.FULL, CategoryConfidence.SUPPORTED)).toList();
        result.add(scenario("ties", ties, empty, "MATCHED", false, null));
        result.add(scenario("platform-conflict", base, empty, "CONFLICT", false, null));
        return List.copyOf(result);
    }

    static Scenario ratingProbe(double rating) {
        var condition = new SearchCondition(2000, null, "ANY", List.of());
        return scenario("rating-probe", List.of(restaurant("probe", "CHINESE", rating, 300, 35,
                DataCompleteness.FULL, CategoryConfidence.VERIFIED)),
                preference(condition, TasteProfile.empty(AS_OF), List.of()), "MATCHED", false, null);
    }

    private static UserPreference preference(SearchCondition condition, TasteProfile profile,
                                              List<RecentFoodHistory.Entry> history) {
        return new UserPreference(condition, profile, Map.of("AMAP:p0", Set.of(FlavorTag.SPICY)),
                new RecentFoodHistory(history, AS_OF));
    }

    private static Scenario scenario(String name, List<Restaurant> restaurants, UserPreference preference,
                                      String mode, boolean stale, String exclude) {
        Instant observed = stale ? INSTANT.minus(Duration.ofDays(180)) : INSTANT;
        var consistency = new CrossPlatformConsistencyAnalyzer(new RiskProperties());
        Map<String, EvidenceBundle> evidence = new LinkedHashMap<>();
        for (Restaurant r : restaurants) {
            PlatformEvidence amap = new AmapEvidenceAdapter().from(r, observed);
            EntityMatchResult match;
            PlatformEvidence baidu = null;
            if (mode.equals("UNAVAILABLE")) match = EntityMatchResult.unavailable();
            else if (mode.equals("NO_MATCH")) match = EntityMatchResult.noMatch();
            else {
                baidu = new PlatformEvidence("BAIDU", "b-" + r.sourcePoiId(), EvidenceStatus.AVAILABLE,
                        observed, r.name(), r.address(), r.latitude(), r.longitude(),
                        mode.equals("CONFLICT") ? 2.0 : r.rating(), 4.5, 4.5, 4.5,
                        100, r.averagePrice(), r.openingHours(), null, r.telephone());
                match = new EntityMatchResult(EntityMatchStatus.MATCHED, .95, baidu, Map.of());
            }
            evidence.put(r.sourcePoiId(), new EvidenceBundle(RestaurantEvidence.empty(), amap, baidu,
                    match, consistency.analyze(amap, match)));
        }
        return new Scenario(name, restaurants, preference, Map.copyOf(evidence), exclude);
    }

    private static Restaurant restaurant(String id, String category, Double rating, int distance,
                                         Integer price, DataCompleteness completeness, CategoryConfidence confidence) {
        return new Restaurant(null, "AMAP", id, "样本" + id, 28.2, 112.9, distance, category, category,
                rating, completeness == DataCompleteness.MINIMAL ? null : 100, price, BusinessStatus.OPEN,
                completeness == DataCompleteness.MINIMAL ? null : "09:00-21:00", "样本地址", "0731-12345678",
                completeness, confidence);
    }
}
