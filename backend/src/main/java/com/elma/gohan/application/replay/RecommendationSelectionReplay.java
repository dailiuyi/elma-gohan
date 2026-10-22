package com.elma.gohan.application.replay;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.*;
import com.elma.gohan.domain.recommendation.v05.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** 离线重放 V7 随机选池与 V9 冻结特征选池，不重算历史风险或重新取证。 */
public final class RecommendationSelectionReplay {
    public record CandidateRef(String source, String sourcePoiId) { }
    public record Input(long randomSeed, int servedPoolSize, double servedExplorationRate,
                        List<SelectionCandidate> selectionSnapshot, List<CandidateRef> servedPool,
                        Integer minBudget, Integer maxBudget, SafeRegretConfig shadowConfiguration,
                        String recordedConfigHash, JsonNode shadowPayload) {
        public Input {
            if (randomSeed == 0) throw new IllegalArgumentException("V7 randomSeed must be nonzero");
            if (servedPoolSize < 0 || servedPoolSize > 6) throw new IllegalArgumentException("Invalid stored pool size");
            if (!Double.isFinite(servedExplorationRate) || servedExplorationRate < 0 || servedExplorationRate > 1)
                throw new IllegalArgumentException("Explicit exploration rate must be in [0,1]");
            selectionSnapshot = List.copyOf(selectionSnapshot);
            servedPool = List.copyOf(servedPool);
            if (servedPool.size() != servedPoolSize) throw new IllegalArgumentException("Stored pool is incomplete");
            Objects.requireNonNull(shadowConfiguration, "Explicit v0.5 selection configuration is required");
            Objects.requireNonNull(recordedConfigHash, "Recorded full config hash is required");
            Objects.requireNonNull(shadowPayload, "V9 payload is required");
        }
    }
    public record Result(String scope, boolean servedPoolMatches, List<CandidateRef> servedPool,
                         List<String> shadowDifferences, SafeRegretDecision shadowDecision,
                         String recordedConfigHash) { }

    private final ObjectMapper mapper;

    public RecommendationSelectionReplay(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }

    public Result replay(Input input) {
        RecommendationProperties properties = new RecommendationProperties();
        properties.setExplorationRate(input.servedExplorationRate());
        var serving = new DefaultRecommendationEngine(new LowRegretScorer(properties), properties, new TasteProperties());
        List<CandidateRef> served = serving.replaySelection(input.selectionSnapshot(), input.servedPoolSize(), input.randomSeed())
                .stream().map(c -> new CandidateRef(c.source(), c.sourcePoiId())).toList();
        SafeRegretDecision shadow = shadow(input.shadowPayload(), input.randomSeed(), input.minBudget(), input.maxBudget(),
                input.shadowConfiguration());
        return new Result("FROZEN_SELECTION_ONLY", served.equals(input.servedPool()), served,
                differences(input.shadowPayload(), shadow), shadow, input.recordedConfigHash());
    }

    public SafeRegretDecision shadow(JsonNode payload, long seed, Integer minBudget, Integer maxBudget,
                                     SafeRegretConfig configuration) {
        if (!required(payload, "featureSchemaVersion").isIntegralNumber()
                || payload.get("featureSchemaVersion").intValue() != 2)
            throw new IllegalArgumentException("Only complete V9 feature schema 2 is supported");
        Set<String> eligible = new LinkedHashSet<>();
        JsonNode manifest = required(payload, "postExclusionManifest");
        if (!manifest.isArray()) throw new IllegalArgumentException("Post-exclusion manifest must be an array");
        for (var item : manifest) {
            if (!eligible.add(required(item, "candidateId").textValue()))
                throw new IllegalArgumentException("Duplicate post-exclusion candidate");
        }
        List<SafeRegretCandidate> candidates = new ArrayList<>();
        Set<String> found = new HashSet<>();
        JsonNode frozen = required(payload, "candidates");
        if (!frozen.isArray()) throw new IllegalArgumentException("Frozen candidates must be an array");
        for (JsonNode item : frozen) {
            String id = required(item, "candidateId").textValue();
            JsonNode frozenFeatures = required(item, "shadowFeatures");
            if (!frozenFeatures.isObject()) throw new IllegalArgumentException("V9 is missing frozen features");
            boolean hasActual = required(item, "shadowActual").isObject();
            if (hasActual != eligible.contains(id)) throw new IllegalArgumentException("V9 manifest/actual mismatch");
            SafeRegretCandidate feature = mapper.convertValue(frozenFeatures, SafeRegretCandidate.class);
            if (!feature.candidateId().equals(id) || !found.add(id))
                throw new IllegalArgumentException("Candidate identity mismatch in V9");
            if (eligible.contains(id)) candidates.add(feature);
        }
        if (!found.containsAll(eligible)) throw new IllegalArgumentException("V9 is missing eligible candidates");
        return new SafeRegretEngine().decide(candidates, new SafeRegretEngine.Request(minBudget, maxBudget), configuration, seed);
    }

    private List<String> differences(JsonNode payload, SafeRegretDecision decision) {
        Map<String, SafeRegretDecision.ScoredCandidate> ranks = new HashMap<>();
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < decision.rankedCandidates().size(); i++) {
            var candidate = decision.rankedCandidates().get(i);
            ranks.put(candidate.candidate().candidateId(), candidate);
            positions.put(candidate.candidate().candidateId(), i + 1);
        }
        Map<String, SafeRegretDecision.Selection> slots = new HashMap<>();
        decision.pool().forEach(s -> slots.put(s.candidate().candidate().candidateId(), s));
        List<String> differences = new ArrayList<>();
        for (JsonNode item : payload.get("candidates")) {
            JsonNode actual = required(item, "shadowActual");
            if (actual.isNull()) continue; // 原请求明确排除的候选不参与 actual 重放。
            String id = required(item, "candidateId").textValue();
            var score = ranks.get(id);
            var selection = slots.get(id);
            compare(differences, id, "score", score == null ? null : score.score(), required(actual, "score"));
            compare(differences, id, "rank", positions.get(id), required(actual, "rank"));
            compare(differences, id, "slot", selection == null ? null : selection.slot(), required(actual, "slot"));
            compare(differences, id, "firstChoicePropensity", decision.firstChoicePropensities().getOrDefault(id, 0.0),
                    required(actual, "firstChoicePropensity"));
            compare(differences, id, "selectionPropensity", selection == null ? null : selection.propensity(),
                    required(actual, "selectionPropensity"));
            compare(differences, id, "selectionObjective", selection == null ? null : selection.objective(),
                    required(actual, "selectionObjective"));
        }
        return List.copyOf(differences);
    }

    private static void compare(List<String> differences, String id, String field, Number actual, JsonNode expected) {
        if (actual == null ? !expected.isNull()
                : !expected.isNumber() || Math.abs(actual.doubleValue() - expected.doubleValue()) > 1e-9)
            differences.add(id + ":" + field);
    }

    private static JsonNode required(JsonNode parent, String key) {
        JsonNode value = parent.get(key);
        if (value == null) throw new IllegalArgumentException("Incomplete replay input: " + key);
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RecommendationSelectionReplay input.json output.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        Input input = mapper.readValue(Path.of(args[0]).toFile(), Input.class);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),
                new RecommendationSelectionReplay(mapper).replay(input));
    }
}
