package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.PersonalizationSnapshot;
import com.elma.gohan.domain.recommendation.RecentFoodHistory;
import com.elma.gohan.domain.recommendation.RecommendationResult;
import com.elma.gohan.domain.recommendation.RestaurantCandidate;
import com.elma.gohan.domain.recommendation.SelectionCandidate;
import com.elma.gohan.domain.recommendation.TasteProfile;
import com.elma.gohan.domain.recommendation.UserPreference;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskFactors;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotRepository;
import com.elma.gohan.provider.evidence.CrossPlatformConsistency;
import com.elma.gohan.provider.evidence.EntityMatchResult;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SafeRegretShadowServiceTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 30, 12, 0);
    private static final Instant AS_OF_INSTANT = AS_OF.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void disabledShadowDoesNotCalculateOrPersist() {
        SafeRegretProperties properties = new SafeRegretProperties();
        properties.setShadowEnabled(false);
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        SafeRegretShadowService service = new SafeRegretShadowService(
                properties, new TasteProperties(), repository, objectMapper);

        assertThat(service.capture(input())).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void capturesCompletePreExclusionCandidatesAndMarksExcludedCandidate()
            throws Exception {
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SafeRegretShadowService service = new SafeRegretShadowService(
                new SafeRegretProperties(), new TasteProperties(), repository, objectMapper);

        RecommendationDecisionSnapshotEntity entity = service.capture(input()).orElseThrow();
        JsonNode payload = objectMapper.readTree(entity.getAllCandidatesJson());

        assertThat(entity.getVariant()).isEqualTo("SHADOW");
        assertThat(entity.getShadowRiskAlgorithmVersion()).isEqualTo("risk-v0.5");
        assertThat(entity.getShadowRecommendationAlgorithmVersion())
                .isEqualTo("recommendation-v0.5");
        assertThat(entity.getFeatureSchemaVersion()).isEqualTo(2);
        assertThat(entity.getConfigHash()).startsWith("sha256:").hasSize(71);
        assertThat(payload.path("featureSchemaVersion").asInt()).isEqualTo(2);
        assertThat(payload.path("preExclusionManifest")).hasSize(3);
        assertThat(payload.path("postExclusionManifest")).hasSize(2);
        assertThat(payload.path("candidates")).hasSize(3);
        JsonNode legacyHigh = find(payload.path("candidates"), "legacy-high");
        assertThat(legacyHigh.path("servedBlocked").asBoolean()).isTrue();
        assertThat(legacyHigh.path("servedRank").isNull()).isTrue();
        assertThat(legacyHigh.path("shadowRisk").path("decisionTier").asText())
                .isEqualTo("UNCERTAIN");
        assertThat(legacyHigh.path("shadowRisk").path("blocked").asBoolean()).isFalse();
        assertThat(legacyHigh.path("shadowRisk").path("factors")).hasSize(4);
        assertThat(legacyHigh.path("shadowRisk").path("posteriorAlpha").isNumber()).isTrue();
        assertThat(legacyHigh.path("shadowCounterfactual").path("rank").isInt()).isTrue();
        assertThat(legacyHigh.path("shadowCounterfactual")
                .path("firstChoicePropensity").isNumber()).isTrue();
        assertThat(legacyHigh.path("shadowCounterfactual")
                .path("breakdown").isObject()).isTrue();
        assertThat(legacyHigh.path("shadowActual").path("rank").isInt()).isTrue();
        assertThat(legacyHigh.path("shadowActual").path("breakdown").isObject()).isTrue();
        assertThat(legacyHigh.path("shadowActual").path("score").asDouble())
                .isNotEqualTo(legacyHigh.path("shadowCounterfactual")
                        .path("score").asDouble());
        JsonNode excluded = find(payload.path("candidates"), "excluded");
        assertThat(excluded.path("restaurant").path("name").asText()).isNotBlank();
        assertThat(excluded.path("evidence").path("amap").path("status").asText())
                .isEqualTo("AVAILABLE");
        assertThat(excluded.path("servedRisk").path("algorithmVersion").asText())
                .isEqualTo("risk-v0.3.1");
        assertThat(excluded.path("shadowRisk").path("algorithmVersion").asText())
                .isEqualTo("risk-v0.5");
        assertThat(excluded.path("shadowCounterfactual").path("rank").isInt()).isTrue();
        assertThat(excluded.path("shadowCounterfactual").path("score").isNumber()).isTrue();
        assertThat(excluded.path("shadowCounterfactual")
                .path("breakdown").isObject()).isTrue();
        assertThat(excluded.path("shadowActualExclusionReason").asText())
                .isEqualTo("EXCLUDED_BY_REQUEST");
        assertThat(excluded.path("shadowActual").isNull()).isTrue();
        assertThat(excluded.has("shadowScore")).isFalse();
        assertThat(excluded.has("shadowRank")).isFalse();
        assertThat(excluded.has("shadowBreakdown")).isFalse();
        assertThat(payload.path("shadowDecisionStatus").asText()).isEqualTo("SELECTED");
        assertThat(payload.path("counterfactualDecisionStatus").asText())
                .isEqualTo("SELECTED");
    }

    @Test
    void futurePlatformRatingCannotBoostQualityWhenRiskFreshnessRejectsIt() throws Exception {
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SafeRegretShadowService service = new SafeRegretShadowService(
                new SafeRegretProperties(), new TasteProperties(), repository, objectMapper);
        SafeRegretShadowInput base = input();
        Restaurant served = base.preExclusionRestaurants().get(0);
        PlatformEvidence future = new PlatformEvidence("AMAP", served.sourcePoiId(),
                EvidenceStatus.AVAILABLE, AS_OF_INSTANT.plusSeconds(3600), served.name(),
                served.address(), served.latitude(), served.longitude(), 5.0, null, null,
                null, 100, served.averagePrice(), served.openingHours(), null, null);
        Map<String, EvidenceBundle> evidence = new java.util.HashMap<>(base.evidenceByPoiId());
        evidence.put(served.sourcePoiId(), new EvidenceBundle(
                RestaurantEvidence.noData("REVIEWS"), future, null,
                EntityMatchResult.noMatch(), CrossPlatformConsistency.unknown("no match")));
        SafeRegretShadowInput withFuture = new SafeRegretShadowInput(base.anonymousUserId(),
                base.recommendationLogId(), base.preExclusionRestaurants(),
                base.eligibleRestaurants(), evidence, base.servedRiskByPoiId(),
                base.userPreference(), base.servedResult(), base.randomSeed(), base.occurredAt());

        JsonNode payload = objectMapper.readTree(
                service.capture(withFuture).orElseThrow().getAllCandidatesJson());

        assertThat(find(payload.path("candidates"), served.sourcePoiId())
                .path("shadowFeatures").path("qualityUtility").isNull()).isTrue();
    }

    @Test
    void identicalFrozenInputProducesReplayStablePayloadAndPropensity() {
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SafeRegretShadowService service = new SafeRegretShadowService(
                new SafeRegretProperties(), new TasteProperties(), repository, objectMapper);

        RecommendationDecisionSnapshotEntity first = service.capture(input()).orElseThrow();
        RecommendationDecisionSnapshotEntity replay = service.capture(input()).orElseThrow();

        assertThat(replay.getAllCandidatesJson()).isEqualTo(first.getAllCandidatesJson());
        assertThat(replay.getConfigHash()).isEqualTo(first.getConfigHash());
        assertThat(replay.getSelectionPropensity()).isEqualTo(first.getSelectionPropensity());
        assertThat(replay.getId()).isEqualTo(first.getId());
    }

    @Test
    void retryReturnsExistingSnapshotWithoutRecomputingOrWritingAgain() {
        RecommendationDecisionSnapshotRepository repository =
                mock(RecommendationDecisionSnapshotRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SafeRegretShadowService service = new SafeRegretShadowService(
                new SafeRegretProperties(), new TasteProperties(), repository, objectMapper);
        SafeRegretShadowInput input = input();
        RecommendationDecisionSnapshotEntity existing = service.capture(input).orElseThrow();
        when(repository.findByRecommendationLogIdAndExperimentKey(
                input.recommendationLogId(), "safe-regret-v0.5"))
                .thenReturn(java.util.Optional.of(existing));

        assertThat(service.capture(input)).contains(existing);
        verify(repository, org.mockito.Mockito.times(1)).saveAndFlush(any());
    }

    @Test
    void configHashIncludesTasteProjectionParameters() {
        RecommendationDecisionSnapshotRepository firstRepository =
                mock(RecommendationDecisionSnapshotRepository.class);
        RecommendationDecisionSnapshotRepository secondRepository =
                mock(RecommendationDecisionSnapshotRepository.class);
        when(firstRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secondRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TasteProperties firstTaste = new TasteProperties();
        TasteProperties secondTaste = new TasteProperties();
        secondTaste.setMaxAbsoluteWeight(firstTaste.getMaxAbsoluteWeight() + 1.0);

        RecommendationDecisionSnapshotEntity first = new SafeRegretShadowService(
                new SafeRegretProperties(), firstTaste, firstRepository, objectMapper)
                .capture(input()).orElseThrow();
        RecommendationDecisionSnapshotEntity second = new SafeRegretShadowService(
                new SafeRegretProperties(), secondTaste, secondRepository, objectMapper)
                .capture(input()).orElseThrow();

        assertThat(second.getConfigHash()).isNotEqualTo(first.getConfigHash());
    }

    private SafeRegretShadowInput input() {
        Restaurant served = TestRestaurants.full("served", 4.6, 300, 30);
        Restaurant legacyHigh = TestRestaurants.full("legacy-high", 4.5, 500, 35);
        Restaurant excluded = TestRestaurants.full("excluded", 4.8, 200, 25);
        RiskResult low = risk(10, RiskLevel.LOW);
        RiskResult high = risk(80, RiskLevel.HIGH);
        RecommendationResult servedResult = new RecommendationResult(
                List.of(new RestaurantCandidate(served, low, 80.0, List.of("旧首推"),
                        PersonalizationSnapshot.neutral("taste-v0.1"))),
                "recommendation-v0.4.1", 42L,
                List.of(new SelectionCandidate(served.source(), served.sourcePoiId(),
                        "MEAL", 80.0, true, false)));
        UserPreference preference = new UserPreference(
                new SearchCondition(1000, null, "ANY", List.of()),
                TasteProfile.empty(AS_OF), Map.of(), RecentFoodHistory.empty(AS_OF));
        return new SafeRegretShadowInput(UUID.fromString(
                "11111111-1111-1111-1111-111111111111"), UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                List.of(served, legacyHigh, excluded), List.of(served, legacyHigh),
                Map.of("served", evidence(served), "legacy-high", evidence(legacyHigh),
                        "excluded", evidence(excluded)),
                Map.of("served", low, "legacy-high", high, "excluded", low), preference,
                servedResult, 42L, AS_OF);
    }

    private EvidenceBundle evidence(Restaurant restaurant) {
        PlatformEvidence amap = new PlatformEvidence("AMAP", restaurant.sourcePoiId(),
                EvidenceStatus.AVAILABLE, AS_OF_INSTANT, restaurant.name(), restaurant.address(),
                restaurant.latitude(), restaurant.longitude(), restaurant.rating(), null, null,
                null, null, restaurant.averagePrice(), restaurant.openingHours(), null, null);
        return new EvidenceBundle(RestaurantEvidence.noData("REVIEWS"), amap, null,
                EntityMatchResult.noMatch(), CrossPlatformConsistency.unknown("no match"));
    }

    private RiskResult risk(int score, RiskLevel level) {
        return new RiskResult(score, level, 0.5, RiskFactors.empty(),
                List.of("旧风险"), "risk-v0.3.1");
    }

    private JsonNode find(JsonNode candidates, String sourcePoiId) {
        for (JsonNode candidate : candidates) {
            if (sourcePoiId.equals(candidate.path("restaurant").path("sourcePoiId").asText())) {
                return candidate;
            }
        }
        throw new AssertionError("candidate not found: " + sourcePoiId);
    }
}
