package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.elma.gohan.application.replay.RecommendationSelectionReplay;
import com.elma.gohan.config.*;
import com.elma.gohan.domain.recommendation.*;
import com.elma.gohan.domain.recommendation.v05.*;
import com.elma.gohan.domain.restaurant.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecommendationReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SafeRegretShadowCalculator calculator = new SafeRegretShadowCalculator(
            new SafeRegretProperties(), new TasteProperties(), mapper);
    private final RecommendationSelectionReplay replay = new RecommendationSelectionReplay(mapper);

    private static final class Variant {
        int decisions, firstChanges, poolChanges, empty;
        double largestCandidateScoreChange;
        void add(List<String> baseline, List<String> variant) {
            decisions++;
            if (!Objects.equals(first(baseline), first(variant))) firstChanges++;
            if (!baseline.equals(variant)) poolChanges++;
            if (variant.isEmpty()) empty++;
        }
        void add(RecommendationResult baseline, RecommendationResult variant) {
            add(servedIds(baseline), servedIds(variant));
            Map<String, Double> scores = new HashMap<>();
            baseline.selectionSnapshot().forEach(c -> scores.put(c.candidateKey(), c.lowRegretScore()));
            variant.selectionSnapshot().forEach(c -> largestCandidateScoreChange = Math.max(largestCandidateScoreChange,
                    Math.abs(c.lowRegretScore() - scores.get(c.candidateKey()))));
        }
        void add(SafeRegretDecision baseline, SafeRegretDecision variant) {
            add(shadowIds(baseline), shadowIds(variant));
            Map<String, Double> scores = new HashMap<>();
            baseline.rankedCandidates().forEach(c -> scores.put(c.candidate().candidateId(), c.score()));
            variant.rankedCandidates().forEach(c -> largestCandidateScoreChange = Math.max(largestCandidateScoreChange,
                    Math.abs(c.score() - scores.get(c.candidate().candidateId()))));
        }
        Map<String, Object> summary() {
            return Map.of("decisions", decisions, "firstChanges", firstChanges, "poolChanges", poolChanges, "empty", empty,
                    "largestCandidateScoreChange", largestCandidateScoreChange);
        }
    }

    @Test
    void replaysV7AndV9AndWritesSyntheticAblationReport() throws Exception {
        Map<String, Variant> variants = new LinkedHashMap<>();
        for (String name : List.of("v04-without-risk-ranking", "v04-quality-completeness-neutral",
                "v04-without-recent-diversity", "v05-without-uncertainty-penalty",
                "v05-quality-neutral", "v05-without-recent-penalty")) variants.put(name, new Variant());
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Long> servingNanos = new ArrayList<>();
        List<Long> shadowNanos = new ArrayList<>();
        int firstChanges = 0, legacyEmpty = 0, shadowEmpty = 0, fallbacks = 0;
        double maximumLegacyGap = 0;
        RecommendationProperties servingConfig = new RecommendationProperties();
        var engine = engine(servingConfig);
        for (var scenario : RecommendationReplayFixtures.scenarios()) {
            int changed = 0;
            double largestGap = 0;
            Set<String> legacyFirstChoices = new TreeSet<>();
            Set<String> shadowFirstChoices = new TreeSet<>();
            var risks = scenario.risks();
            var eligible = scenario.eligible();
            // 每场景预热，不将首次类加载当成算法计算耗时。
            engine.recommend(eligible, risks, scenario.preference(), 42);
            calculator.calculate(scenario.input(42));
            for (long seed : RecommendationReplayFixtures.SEEDS) {
                long began = System.nanoTime();
                var served = engine.recommend(eligible, risks, scenario.preference(), seed);
                servingNanos.add(System.nanoTime() - began);
                var input = new SafeRegretShadowInput(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        UUID.nameUUIDFromBytes((scenario.name() + seed).getBytes(StandardCharsets.UTF_8)),
                        scenario.hardEligible(), eligible, scenario.evidence(), risks, scenario.preference(),
                        served, seed, RecommendationReplayFixtures.AS_OF);
                began = System.nanoTime();
                var snapshot = calculator.calculate(input);
                shadowNanos.add(System.nanoTime() - began);
                JsonNode payload = mapper.readTree(snapshot.getAllCandidatesJson());
                var request = replayInput(input, payload);
                var result = replay.replay(request);
                assertThat(result.servedPoolMatches()).as(scenario.name() + "/" + seed + " V7").isTrue();
                assertThat(result.shadowDifferences()).as(scenario.name() + "/" + seed + " V9").isEmpty();
                List<String> oldPool = servedIds(served);
                List<String> newPool = shadowIds(result.shadowDecision());
                if (!Objects.equals(first(oldPool), first(newPool))) { changed++; firstChanges++; }
                if (oldPool.isEmpty()) legacyEmpty++; else legacyFirstChoices.add(first(oldPool));
                if (newPool.isEmpty()) shadowEmpty++; else {
                    shadowFirstChoices.add(first(newPool));
                    if (result.shadowDecision().pool().get(0).reason().equals("LOWEST_CONSERVATIVE_RISK_FALLBACK")) fallbacks++;
                }
                if (!oldPool.isEmpty()) {
                    var selected = served.pool().get(0).restaurant();
                    double selectedRaw = served.selectionSnapshot().stream()
                            .filter(c -> c.source().equals(selected.source()) && c.sourcePoiId().equals(selected.sourcePoiId()))
                            .findFirst().orElseThrow().lowRegretScore();
                    double gap = served.selectionSnapshot().get(0).lowRegretScore() - selectedRaw;
                    largestGap = Math.max(largestGap, gap);
                    maximumLegacyGap = Math.max(maximumLegacyGap, gap);
                }
                RecommendationProperties noRisk = new RecommendationProperties();
                noRisk.getWeights().setRiskSafety(0);
                variants.get("v04-without-risk-ranking").add(served, engine(noRisk)
                        .recommend(eligible, risks, scenario.preference(), seed));
                var neutralCompleteness = eligible.stream().map(RecommendationReplayTest::fullCompleteness).toList();
                variants.get("v04-quality-completeness-neutral").add(served, engine(servingConfig)
                        .recommend(neutralCompleteness, risks, scenario.preference(), seed));
                RecommendationProperties noDiversity = new RecommendationProperties();
                noDiversity.getWeights().setRecentDiversity(0);
                variants.get("v04-without-recent-diversity").add(served, engine(noDiversity)
                        .recommend(eligible, risks, scenario.preference(), seed));
                variants.get("v05-without-uncertainty-penalty").add(result.shadowDecision(), replay.shadow(payload, seed,
                        scenario.preference().condition().minBudget(), scenario.preference().condition().maxBudget(), withoutUncertainty()));
                for (String field : List.of("qualityUtility", "recentExposurePenalty")) {
                    ObjectNode neutral = payload.deepCopy();
                    for (var candidate : neutral.withArray("candidates"))
                        ((ObjectNode) candidate.get("shadowFeatures")).put(field, field.equals("qualityUtility") ? .70 : 0);
                    String variant = field.equals("qualityUtility") ? "v05-quality-neutral" : "v05-without-recent-penalty";
                    variants.get(variant).add(result.shadowDecision(), replay.shadow(neutral, seed,
                            scenario.preference().condition().minBudget(), scenario.preference().condition().maxBudget(), SafeRegretConfig.defaults()));
                }
                if (cases.isEmpty() && seed == RecommendationReplayFixtures.SEEDS[0]) {
                    Files.createDirectories(Path.of("target/recommendation-replay"));
                    mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/recommendation-replay/example-input.json").toFile(), request);
                }
            }
            cases.add(Map.of("scenario", scenario.name(), "decisions", RecommendationReplayFixtures.SEEDS.length,
                    "firstChanges", changed, "largestLegacyScoreGap", largestGap,
                    "legacyFirstChoices", legacyFirstChoices, "shadowFirstChoices", shadowFirstChoices));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sampleKind", "SYNTHETIC_BOUNDARY_CASES_NOT_USER_ACCEPTANCE");
        report.put("decisions", servingNanos.size());
        report.put("firstChanges", firstChanges);
        report.put("legacyEmpty", legacyEmpty);
        report.put("shadowEmpty", shadowEmpty);
        report.put("shadowLowestRiskFallbacks", fallbacks);
        report.put("largestLegacyScoreGap", maximumLegacyGap);
        report.put("controlledSensitivity", controlledSensitivity());
        Map<String, Object> ablations = new LinkedHashMap<>();
        variants.forEach((name, stats) -> ablations.put(name, stats.summary()));
        report.put("ablations", ablations);
        report.put("cases", cases);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/recommendation-replay/report.json").toFile(), report);
        assertThat(servingNanos).hasSize(104);
        try (var expected = Files.newInputStream(Path.of("../docs/reports/recommendation-replay-20261022.json"))) {
            assertThat(mapper.<JsonNode>valueToTree(report)).as("Frozen deterministic replay and all six ablations")
                    .isEqualTo(mapper.readTree(expected));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "LIKE,7,false", "NORMAL,7,false", "DISLIKE,30,false", "DISLIKE,31,true", "LIKE,8,true"
    })
    void explorationHonorsSevenDayMealsAndThirtyDayDislikes(String feedback, int ageDays, boolean eligible) {
        var scenario = RecommendationReplayFixtures.ratingProbe(4.8);
        var restaurant = scenario.restaurants().get(0);
        var preference = new UserPreference(scenario.preference().condition(), scenario.preference().tasteProfile(), Map.of(),
                new RecentFoodHistory(List.of(new RecentFoodHistory.Entry("AMAP", "other-store", restaurant.categoryCode(),
                        feedback, RecommendationReplayFixtures.AS_OF.minusDays(ageDays))), RecommendationReplayFixtures.AS_OF));
        var lowRisk = new com.elma.gohan.domain.risk.RiskResult(0, com.elma.gohan.domain.risk.RiskLevel.LOW, List.of(), "risk-v0.3.1");
        var result = engine(new RecommendationProperties()).recommend(List.of(restaurant), Map.of(restaurant.sourcePoiId(), lowRisk), preference, 42);
        assertThat(result.selectionSnapshot()).singleElement().satisfies(candidate ->
                assertThat(candidate.explorationEligible()).isEqualTo(eligible));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LIKE", "NORMAL"})
    void sameRestaurantLikeAndNormalBothCostThirtyPoints(String feedback) {
        var scenario = RecommendationReplayFixtures.ratingProbe(4.8);
        var restaurant = scenario.restaurants().get(0);
        var preference = new UserPreference(scenario.preference().condition(), scenario.preference().tasteProfile(), Map.of(),
                new RecentFoodHistory(List.of(new RecentFoodHistory.Entry("AMAP", restaurant.sourcePoiId(), restaurant.categoryCode(),
                        feedback, RecommendationReplayFixtures.AS_OF.minusDays(7))), RecommendationReplayFixtures.AS_OF));
        var result = engine(new RecommendationProperties()).recommend(List.of(restaurant), scenario.risks(), preference, 42);
        assertThat(result.pool()).singleElement().satisfies(candidate ->
                assertThat(candidate.personalization().scoreBreakdown().recentPenalty()).isEqualTo(30));
    }

    @Test
    void rejectsIncompleteFeaturesAndManifestMismatch() throws Exception {
        var input = RecommendationReplayFixtures.scenarios().get(0).input(42);
        var snapshot = calculator.calculate(input);
        ObjectNode payload = (ObjectNode) mapper.readTree(snapshot.getAllCandidatesJson());
        ((ObjectNode) payload.withArray("candidates").get(0)).remove("shadowFeatures");
        var missingFeatures = replayInput(input, payload);
        assertThatThrownBy(() -> replay.replay(missingFeatures))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shadowFeatures");
        payload = (ObjectNode) mapper.readTree(snapshot.getAllCandidatesJson());
        payload.withArray("postExclusionManifest").removeAll();
        var request = replayInput(input, payload);
        assertThatThrownBy(() -> replay.replay(request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifest/actual");
    }

    private RecommendationSelectionReplay.Input replayInput(SafeRegretShadowInput input, JsonNode payload) {
        return new RecommendationSelectionReplay.Input(input.randomSeed(), input.servedResult().pool().size(), .20,
                input.servedResult().selectionSnapshot(), input.servedResult().pool().stream().map(c ->
                    new RecommendationSelectionReplay.CandidateRef(c.restaurant().source(), c.restaurant().sourcePoiId())).toList(),
                input.userPreference().condition().minBudget(), input.userPreference().condition().maxBudget(),
                SafeRegretConfig.defaults(), payload);
    }

    private static DefaultRecommendationEngine engine(RecommendationProperties properties) {
        return new DefaultRecommendationEngine(new LowRegretScorer(properties), properties, new TasteProperties());
    }
    private static List<String> servedIds(RecommendationResult result) {
        return result.pool().stream().map(c -> Base64.getUrlEncoder().withoutPadding().encodeToString(
                (c.restaurant().source() + "\u0000" + c.restaurant().sourcePoiId()).getBytes(StandardCharsets.UTF_8))).toList();
    }
    private static List<String> shadowIds(SafeRegretDecision decision) {
        return decision.pool().stream().map(c -> c.candidate().candidate().candidateId()).toList();
    }
    private static String first(List<String> pool) { return pool.isEmpty() ? null : pool.get(0); }
    private static Restaurant fullCompleteness(Restaurant r) {
        return completeness(r, DataCompleteness.FULL);
    }
    private static Restaurant completeness(Restaurant r, DataCompleteness completeness) {
        return new Restaurant(r.id(), r.source(), r.sourcePoiId(), r.name(), r.latitude(), r.longitude(), r.distanceMeters(),
                r.categoryCode(), r.categoryLabel(), r.rating(), r.reviewCount(), r.averagePrice(), r.businessStatus(),
                r.openingHours(), r.address(), r.telephone(), completeness, r.categoryConfidence());
    }
    private static Map<String, Double> controlledSensitivity() {
        var strong = RecommendationReplayFixtures.ratingProbe(4.8);
        var weak = RecommendationReplayFixtures.ratingProbe(3.9);
        var scorer = new LowRegretScorer(new RecommendationProperties());
        var before = scorer.details(strong.restaurants().get(0), strong.risks().get("probe"), strong.preference());
        var after = scorer.details(weak.restaurants().get(0), weak.risks().get("probe"), weak.preference());
        var masked = scorer.details(completeness(strong.restaurants().get(0), DataCompleteness.MINIMAL),
                strong.risks().get("probe"), strong.preference());
        double qualityLoss = (before.breakdown().restaurantQuality() - after.breakdown().restaurantQuality()) * .25;
        double safetyLoss = (before.breakdown().riskSafety() - after.breakdown().riskSafety()) * .25;
        assertThat(before.score() - after.score()).isCloseTo(qualityLoss + safetyLoss, org.assertj.core.data.Offset.offset(1e-9));
        return Map.of("rating48to39QualityLoss", qualityLoss, "rating48to39SafetyLoss", safetyLoss,
                "rating48to39TotalLoss", before.score() - after.score(),
                "completenessFullToMinimalQualityOnlyLoss", before.score() - masked.score());
    }
    private static SafeRegretConfig withoutUncertainty() {
        var c = SafeRegretConfig.defaults();
        return new SafeRegretConfig(c.weights(), c.worstRegretBlend(), 0, c.missingUtilityPrior(),
                c.walkingSpeedMetersPerMinute(), c.distanceHalfLifeMinutes(), c.nearTieScoreDelta(), c.softmaxTemperature(),
                c.poolSize(), c.maxDiversityLoss(), c.diversityPenaltyPoints(), c.priceBandWidth(), c.distanceBandWidth());
    }
}
