package com.elma.gohan.application.shadow;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.FlavorTag;
import com.elma.gohan.domain.recommendation.RestaurantCandidate;
import com.elma.gohan.domain.recommendation.SelectionCandidate;
import com.elma.gohan.domain.recommendation.TasteProfile;
import com.elma.gohan.domain.recommendation.v05.SafeRegretCandidate;
import com.elma.gohan.domain.recommendation.v05.SafeRegretConfig;
import com.elma.gohan.domain.recommendation.v05.SafeRegretDecision;
import com.elma.gohan.domain.recommendation.v05.SafeRegretEngine;
import com.elma.gohan.domain.recommendation.v05.StableExperimentAllocator;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorBatchResult;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorConfig;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorDecisionPolicy;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorEvidenceAdapter;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorEvidenceConfig;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorInput;
import com.elma.gohan.domain.risk.posterior.RiskPosteriorResult;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotRepository;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 同请求计算并冻结 SafeRegret v0.5；阶段 1 不参与现有响应。 */
@Service
public class SafeRegretShadowService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SafeRegretProperties properties;
    private final TasteProperties tasteProperties;
    private final SafeRegretShadowSnapshotWriter snapshotWriter;
    private final ObjectMapper objectMapper;
    private final RiskPosteriorConfig posteriorConfig;
    private final RiskPosteriorEvidenceConfig evidenceConfig;
    private final SafeRegretConfig recommendationConfig;
    private final ShadowFeatureConfig featureConfig;
    private final StableExperimentAllocator allocator;
    private final SafeRegretEngine recommendationEngine;

    @Autowired
    public SafeRegretShadowService(
            SafeRegretProperties properties,
            TasteProperties tasteProperties,
            SafeRegretShadowSnapshotWriter snapshotWriter,
            ObjectMapper objectMapper) {
        this(properties, tasteProperties, snapshotWriter, objectMapper,
                RiskPosteriorConfig.defaults(), RiskPosteriorEvidenceConfig.defaults(),
                SafeRegretConfig.defaults(), ShadowFeatureConfig.defaults(),
                new StableExperimentAllocator(), new SafeRegretEngine());
    }

    SafeRegretShadowService(
            SafeRegretProperties properties,
            TasteProperties tasteProperties,
            RecommendationDecisionSnapshotRepository repository,
            ObjectMapper objectMapper) {
        this(properties, tasteProperties, new SafeRegretShadowSnapshotWriter(repository),
                objectMapper, RiskPosteriorConfig.defaults(),
                RiskPosteriorEvidenceConfig.defaults(), SafeRegretConfig.defaults(),
                ShadowFeatureConfig.defaults(), new StableExperimentAllocator(),
                new SafeRegretEngine());
    }

    SafeRegretShadowService(
            SafeRegretProperties properties,
            TasteProperties tasteProperties,
            RecommendationDecisionSnapshotRepository repository,
            ObjectMapper objectMapper,
            RiskPosteriorConfig posteriorConfig,
            RiskPosteriorEvidenceConfig evidenceConfig,
            SafeRegretConfig recommendationConfig,
            ShadowFeatureConfig featureConfig,
            StableExperimentAllocator allocator,
            SafeRegretEngine recommendationEngine) {
        this(properties, tasteProperties, new SafeRegretShadowSnapshotWriter(repository),
                objectMapper, posteriorConfig, evidenceConfig, recommendationConfig,
                featureConfig, allocator, recommendationEngine);
    }

    private SafeRegretShadowService(
            SafeRegretProperties properties,
            TasteProperties tasteProperties,
            SafeRegretShadowSnapshotWriter snapshotWriter,
            ObjectMapper objectMapper,
            RiskPosteriorConfig posteriorConfig,
            RiskPosteriorEvidenceConfig evidenceConfig,
            SafeRegretConfig recommendationConfig,
            ShadowFeatureConfig featureConfig,
            StableExperimentAllocator allocator,
            SafeRegretEngine recommendationEngine) {
        this.properties = properties;
        this.tasteProperties = tasteProperties;
        this.snapshotWriter = snapshotWriter;
        this.objectMapper = objectMapper;
        this.posteriorConfig = posteriorConfig;
        this.evidenceConfig = evidenceConfig;
        this.recommendationConfig = recommendationConfig;
        this.featureConfig = featureConfig;
        this.allocator = allocator;
        this.recommendationEngine = recommendationEngine;
    }

    /** 保存一行 V9 快照；shadow 关闭时不做计算或写库。 */
    public Optional<RecommendationDecisionSnapshotEntity> capture(SafeRegretShadowInput input) {
        if (!properties.isShadowEnabled()) return Optional.empty();
        Optional<RecommendationDecisionSnapshotEntity> existing = snapshotWriter.findExisting(
                input.recommendationLogId(), properties.getExperimentKey());
        if (existing.isPresent()) return existing;

        Instant asOf = input.occurredAt().atZone(ZONE).toInstant();
        RiskPosteriorEvidenceAdapter adapter = new RiskPosteriorEvidenceAdapter(
                posteriorConfig, evidenceConfig);
        List<Restaurant> allCandidates = input.preExclusionRestaurants().isEmpty()
                ? input.eligibleRestaurants() : input.preExclusionRestaurants();
        RiskPosteriorBatchResult posterior = adapter.calculateBatch(
                allCandidates, input.evidenceByPoiId(),
                input.servedRiskByPoiId(), asOf);
        RiskPosteriorDecisionPolicy policy = new RiskPosteriorDecisionPolicy(
                properties.getHighRiskThreshold(), properties.getTrustedSafeThreshold(),
                properties.getMinimumDecisionConfidence());

        Map<String, SafeRegretCandidate> mapped = new LinkedHashMap<>();
        for (Restaurant restaurant : allCandidates) {
            RiskPosteriorResult risk = posterior.posteriors().get(restaurant.sourcePoiId());
            if (risk == null) continue;
            RiskPosteriorDecisionPolicy.DecisionTier tier = policy.classify(risk);
            Set<String> flavors = flavorNames(input, restaurant);
            double tasteConfidence = input.userPreference().tasteProfile()
                    .confidence(tasteProperties);
            SafeRegretCandidate candidate = new SafeRegretCandidate(
                    candidateId(restaurant),
                    new SafeRegretCandidate.RiskView(risk.posteriorMean(),
                            risk.conservativeRisk(), risk.confidence(),
                            tier.trustedSafe(), tier.blocked()),
                    qualityUtility(restaurant,
                            input.evidenceByPoiId().get(restaurant.sourcePoiId()), asOf),
                    restaurant.averagePrice(), restaurant.distanceMeters(),
                    tasteUtility(input, restaurant, flavors), tasteConfidence,
                    clamp(input.userPreference().recentHistory().penalty(restaurant)
                            / featureConfig.recentExposureDivisor()),
                    restaurant.categoryCode(), flavors);
            mapped.put(candidate.candidateId(), candidate);
        }

        SafeRegretDecision allCandidateRanking = recommendationEngine.decide(
                List.copyOf(mapped.values()),
                new SafeRegretEngine.Request(input.userPreference().condition().minBudget(),
                        input.userPreference().condition().maxBudget()),
                recommendationConfig, input.randomSeed());
        Set<String> postExclusionIds = input.eligibleRestaurants().stream()
                .map(this::candidateId).collect(java.util.stream.Collectors.toSet());
        List<SafeRegretCandidate> decisionCandidates = mapped.values().stream()
                .filter(candidate -> postExclusionIds.contains(candidate.candidateId()))
                .toList();
        SafeRegretDecision shadow = recommendationEngine.decide(
                decisionCandidates,
                new SafeRegretEngine.Request(input.userPreference().condition().minBudget(),
                        input.userPreference().condition().maxBudget()),
                recommendationConfig, input.randomSeed());
        StableExperimentAllocator.Allocation allocation = allocator.allocate(
                input.anonymousUserId(), properties.getExperimentKey(),
                properties.isServingEnabled(), properties.getRolloutPercentage());

        String payload = payloadJson(input, posterior, policy, allCandidateRanking, shadow,
                allocation, mapped, postExclusionIds);
        Double propensity = shadow.pool().isEmpty() ? null : shadow.pool().get(0).propensity();
        RecommendationDecisionSnapshotEntity entity = new RecommendationDecisionSnapshotEntity(
                snapshotId(input.recommendationLogId(), properties.getExperimentKey()),
                input.recommendationLogId(), properties.getExperimentKey(),
                allocation.variant().name(), servedRiskVersion(input),
                posteriorConfig.algorithmVersion(), input.servedResult().algorithmVersion(),
                SafeRegretEngine.ALGORITHM_VERSION, input.randomSeed(), propensity,
                configHash(policy), properties.getFeatureSchemaVersion(), payload,
                input.occurredAt());
        return Optional.of(snapshotWriter.save(entity));
    }

    private UUID snapshotId(UUID recommendationLogId, String experimentKey) {
        String stableKey = recommendationLogId + "\u0000" + experimentKey;
        return UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
    }

    private String payloadJson(
            SafeRegretShadowInput input,
            RiskPosteriorBatchResult posterior,
            RiskPosteriorDecisionPolicy policy,
            SafeRegretDecision allCandidateRanking,
            SafeRegretDecision shadow,
            StableExperimentAllocator.Allocation allocation,
            Map<String, SafeRegretCandidate> mapped,
            Set<String> postExclusionIds) {
        Map<String, Integer> servedRanks = servedRanks(input.servedResult().selectionSnapshot());
        Map<String, Integer> servedSlots = servedSlots(input.servedResult().pool());
        DecisionIndex counterfactual = decisionIndex(allCandidateRanking);
        DecisionIndex actual = decisionIndex(shadow);

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Restaurant> snapshotCandidates = input.preExclusionRestaurants().isEmpty()
                ? input.eligibleRestaurants() : input.preExclusionRestaurants();
        for (Restaurant restaurant : snapshotCandidates) {
            String stableKey = internalKey(restaurant);
            String id = candidateId(restaurant);
            RiskResult servedRisk = input.servedRiskByPoiId().get(restaurant.sourcePoiId());
            RiskPosteriorResult shadowRisk = posterior.posteriors().get(restaurant.sourcePoiId());
            SafeRegretCandidate feature = mapped.get(id);

            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", id);
            item.put("restaurant", restaurantSnapshot(restaurant));
            item.put("evidence", evidenceSnapshot(
                    input.evidenceByPoiId().get(restaurant.sourcePoiId())));
            item.put("servedRisk", servedRiskSnapshot(servedRisk));
            item.put("servedBlocked", servedRisk != null
                    && servedRisk.riskLevel() == com.elma.gohan.domain.risk.RiskLevel.HIGH);
            item.put("servedRank", servedRanks.get(stableKey));
            item.put("servedSlot", servedSlots.get(stableKey));
            item.put("shadowRisk", shadowRiskSnapshot(shadowRisk,
                    posterior.inputs().get(restaurant.sourcePoiId()), policy));
            item.put("shadowFeatures", feature);
            item.put("shadowCounterfactual", decisionSnapshot(
                    input, restaurant, shadowRisk, policy, allCandidateRanking,
                    counterfactual, id));
            if (postExclusionIds.contains(id)) {
                item.put("shadowActual", decisionSnapshot(
                        input, restaurant, shadowRisk, policy, shadow, actual, id));
                item.put("shadowActualExclusionReason", null);
            } else {
                item.put("shadowActual", null);
                item.put("shadowActualExclusionReason", "EXCLUDED_BY_REQUEST");
            }
            candidates.add(item);
        }

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("featureSchemaVersion", properties.getFeatureSchemaVersion());
        root.put("asOf", input.occurredAt());
        root.put("experimentBucket", allocation.bucket());
        root.put("platformRatingBias", posterior.amapMinusBaiduMedianBias());
        root.put("platformCalibrationPairCount", posterior.calibrationPairCount());
        root.put("preExclusionManifest", manifest(input.preExclusionRestaurants()));
        root.put("postExclusionManifest", manifest(input.eligibleRestaurants()));
        root.put("counterfactualDecisionStatus",
                allCandidateRanking.pool().isEmpty() ? "NO_SELECTABLE" : "SELECTED");
        root.put("shadowDecisionStatus", shadow.pool().isEmpty() ? "NO_SELECTABLE" : "SELECTED");
        root.put("candidates", candidates);
        return writeJson(root, "SafeRegret shadow 快照序列化失败");
    }

    private DecisionIndex decisionIndex(SafeRegretDecision decision) {
        Map<String, SafeRegretDecision.ScoredCandidate> scores = new HashMap<>();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < decision.rankedCandidates().size(); i++) {
            SafeRegretDecision.ScoredCandidate score = decision.rankedCandidates().get(i);
            scores.put(score.candidate().candidateId(), score);
            ranks.put(score.candidate().candidateId(), i + 1);
        }
        Map<String, SafeRegretDecision.Selection> slots = new HashMap<>();
        for (SafeRegretDecision.Selection selection : decision.pool()) {
            slots.put(selection.candidate().candidate().candidateId(), selection);
        }
        return new DecisionIndex(Map.copyOf(scores), Map.copyOf(ranks), Map.copyOf(slots));
    }

    private Map<String, Object> decisionSnapshot(
            SafeRegretShadowInput input,
            Restaurant restaurant,
            RiskPosteriorResult shadowRisk,
            RiskPosteriorDecisionPolicy policy,
            SafeRegretDecision decision,
            DecisionIndex index,
            String candidateId) {
        SafeRegretDecision.ScoredCandidate score = index.scores().get(candidateId);
        SafeRegretDecision.Selection selection = index.slots().get(candidateId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("score", score == null ? null : score.score());
        result.put("rank", index.ranks().get(candidateId));
        result.put("breakdown", score == null ? null : score.breakdown());
        result.put("firstChoicePropensity",
                decision.firstChoicePropensities().getOrDefault(candidateId, 0.0));
        result.put("slot", selection == null ? null : selection.slot());
        result.put("selectionPropensity", selection == null ? null : selection.propensity());
        result.put("selectionObjective", selection == null ? null : selection.objective());
        result.put("selectionReason", selectionReason(
                input, restaurant, shadowRisk, policy, score, selection));
        return result;
    }

    private record DecisionIndex(
            Map<String, SafeRegretDecision.ScoredCandidate> scores,
            Map<String, Integer> ranks,
            Map<String, SafeRegretDecision.Selection> slots) { }

    private List<Map<String, String>> manifest(List<Restaurant> restaurants) {
        return restaurants.stream().<Map<String, String>>map(restaurant -> {
            LinkedHashMap<String, String> item = new LinkedHashMap<>();
            item.put("source", restaurant.source());
            item.put("sourcePoiId", restaurant.sourcePoiId());
            item.put("candidateId", candidateId(restaurant));
            return item;
        }).toList();
    }

    private Map<String, Integer> servedRanks(List<SelectionCandidate> snapshot) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < snapshot.size(); i++) {
            SelectionCandidate candidate = snapshot.get(i);
            result.put(internalKey(candidate.source(), candidate.sourcePoiId()), i + 1);
        }
        return result;
    }

    private Map<String, Integer> servedSlots(List<RestaurantCandidate> pool) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < pool.size(); i++) {
            result.put(internalKey(pool.get(i).restaurant()), i + 1);
        }
        return result;
    }

    private Map<String, Object> restaurantSnapshot(Restaurant restaurant) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", restaurant.id());
        result.put("source", restaurant.source());
        result.put("sourcePoiId", restaurant.sourcePoiId());
        result.put("name", restaurant.name());
        result.put("latitude", restaurant.latitude());
        result.put("longitude", restaurant.longitude());
        result.put("distanceMeters", restaurant.distanceMeters());
        result.put("categoryCode", restaurant.categoryCode());
        result.put("categoryLabel", restaurant.categoryLabel());
        result.put("categoryConfidence", restaurant.categoryConfidence());
        result.put("rating", restaurant.rating());
        result.put("reviewCount", restaurant.reviewCount());
        result.put("averagePrice", restaurant.averagePrice());
        result.put("businessStatus", restaurant.businessStatus());
        result.put("openingHours", restaurant.openingHours());
        result.put("address", restaurant.address());
        result.put("telephone", restaurant.telephone());
        result.put("dataCompleteness", restaurant.dataCompleteness());
        return result;
    }

    private Map<String, Object> evidenceSnapshot(EvidenceBundle bundle) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (bundle == null) {
            result.put("status", "MISSING_BUNDLE");
            return result;
        }
        result.put("reviewStatus", bundle.reviewEvidence().status());
        result.put("reviewCount", bundle.reviewEvidence().reviews().size());
        result.put("reviewFetchedAt", bundle.reviewEvidence().fetchedAt());
        result.put("entityMatchStatus", bundle.entityMatch().status());
        result.put("entityMatchConfidence", bundle.entityMatch().confidence());
        result.put("consistency", bundle.consistency().level());
        result.put("ratingDifference", bundle.consistency().ratingDifference());
        result.put("amap", platformSnapshot(bundle.amap()));
        result.put("baidu", platformSnapshot(bundle.baidu()));
        return result;
    }

    private Map<String, Object> platformSnapshot(PlatformEvidence evidence) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (evidence == null) {
            result.put("status", "NO_DATA");
            return result;
        }
        result.put("status", evidence.status());
        result.put("observedAt", evidence.observedAt());
        result.put("rating", evidence.overallRating());
        result.put("commentCount", evidence.commentCount());
        result.put("averagePrice", evidence.averagePrice());
        return result;
    }

    private Map<String, Object> servedRiskSnapshot(RiskResult risk) {
        if (risk == null) return Map.of("status", "MISSING");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("score", risk.riskScore());
        result.put("level", risk.riskLevel());
        result.put("confidence", risk.confidence());
        result.put("factors", risk.factors());
        result.put("algorithmVersion", risk.algorithmVersion());
        return result;
    }

    private Map<String, Object> shadowRiskSnapshot(
            RiskPosteriorResult risk,
            RiskPosteriorInput input,
            RiskPosteriorDecisionPolicy policy) {
        if (risk == null) return Map.of("status", "MISSING");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("posteriorAlpha", risk.posteriorAlpha());
        result.put("posteriorBeta", risk.posteriorBeta());
        result.put("posteriorMean", risk.posteriorMean());
        result.put("conservativeRisk", risk.conservativeRisk());
        result.put("confidence", risk.confidence());
        result.put("intervalLower", risk.intervalLower());
        result.put("intervalUpper", risk.intervalUpper());
        result.put("effectiveEvidenceMass", risk.effectiveEvidenceMass());
        result.put("observedFactorCount", risk.observedFactorCount());
        result.put("missingFactorCount", risk.missingFactorCount());
        result.put("factors", input == null ? List.of() : input.factors());
        result.put("decisionTier", policy.classify(risk));
        result.put("trustedSafe", policy.isTrustedSafe(risk));
        result.put("blocked", policy.isBlocked(risk));
        result.put("algorithmVersion", risk.algorithmVersion());
        return result;
    }

    private Double qualityUtility(Restaurant restaurant, EvidenceBundle bundle, Instant asOf) {
        double weighted = 0.0;
        double mass = 0.0;
        PlatformEvidence amap = bundle == null ? null : bundle.amap();
        boolean amapAvailable = available(amap);
        if (amapAvailable && validRating(amap.overallRating())) {
            double sample = sampleMass(amap.commentCount())
                    * freshness(amap.observedAt(), asOf,
                    evidenceConfig.rating().freshnessHalfLifeDays());
            if (sample > 0.0) {
                weighted += sample * ratingUtility(amap.overallRating());
                mass += sample;
            }
        }
        if (!amapAvailable && validRating(restaurant.rating())) {
            double sample = sampleMass(restaurant.reviewCount());
            weighted += sample * ratingUtility(restaurant.rating());
            mass += sample;
        }
        PlatformEvidence baidu = bundle == null ? null : bundle.baidu();
        if (available(baidu) && bundle.entityMatch().status() == EntityMatchStatus.MATCHED
                && validRating(baidu.overallRating())) {
            double match = bundle.entityMatch().confidence() == null
                    ? 0.0 : clamp(bundle.entityMatch().confidence());
            double sample = sampleMass(baidu.commentCount()) * match
                    * freshness(baidu.observedAt(), asOf,
                    evidenceConfig.rating().freshnessHalfLifeDays());
            weighted += sample * ratingUtility(baidu.overallRating());
            mass += sample;
        }
        if (mass == 0.0) return null;
        return clamp((featureConfig.qualityPriorMean() * featureConfig.qualityPriorStrength()
                + weighted) / (featureConfig.qualityPriorStrength() + mass));
    }

    private double tasteUtility(SafeRegretShadowInput input, Restaurant restaurant,
                                Set<String> flavors) {
        TasteProfile profile = input.userPreference().tasteProfile();
        double category = preferenceUtility(profile.categoryWeight(restaurant));
        double flavor = 0.5;
        if (!flavors.isEmpty()) {
            flavor = flavors.stream().map(FlavorTag::valueOf)
                    .mapToDouble(tag -> preferenceUtility(profile.flavorWeight(tag)))
                    .average().orElse(0.5);
        }
        return clamp(featureConfig.categoryTasteShare() * category
                + (1.0 - featureConfig.categoryTasteShare()) * flavor);
    }

    private Set<String> flavorNames(SafeRegretShadowInput input, Restaurant restaurant) {
        TreeSet<String> result = new TreeSet<>();
        input.userPreference().flavorTags(restaurant).stream()
                .map(FlavorTag::name).forEach(result::add);
        return java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(result));
    }

    private double preferenceUtility(double weight) {
        double scale = Math.max(0.0001, tasteProperties.getMaxAbsoluteWeight());
        return 0.5 + 0.5 * Math.max(-1.0, Math.min(1.0, weight / scale));
    }

    private double sampleMass(Integer count) {
        if (count == null) return 1.0;
        return Math.max(0.0, Math.min(100.0, count));
    }

    private double ratingUtility(double rating) {
        return clamp((rating - 1.0) / 4.0);
    }

    private boolean validRating(Double rating) {
        return rating != null && Double.isFinite(rating) && rating >= 1.0 && rating <= 5.0;
    }

    private boolean available(PlatformEvidence evidence) {
        return evidence != null && evidence.status() == EvidenceStatus.AVAILABLE;
    }

    private double freshness(Instant observedAt, Instant asOf, int halfLifeDays) {
        if (observedAt == null || observedAt.isAfter(asOf)) return 0.0;
        double ageDays = Duration.between(observedAt, asOf).toSeconds() / 86400.0;
        return Math.exp(-Math.log(2.0) * ageDays / halfLifeDays);
    }

    private String selectionReason(
            SafeRegretShadowInput input,
            Restaurant restaurant,
            RiskPosteriorResult shadowRisk,
            RiskPosteriorDecisionPolicy policy,
            SafeRegretDecision.ScoredCandidate shadowScore,
            SafeRegretDecision.Selection shadowSlot) {
        if (shadowSlot != null) return shadowSlot.reason();
        if (shadowRisk == null) return "MISSING_RISK";
        if (policy.isBlocked(shadowRisk)) return "RISK_BLOCKED";
        Integer price = restaurant.averagePrice();
        Integer minBudget = input.userPreference().condition().minBudget();
        Integer maxBudget = input.userPreference().condition().maxBudget();
        if (price != null && ((minBudget != null && price <= minBudget)
                || (maxBudget != null && price > maxBudget))) {
            return "BUDGET_OUT_OF_RANGE";
        }
        if (shadowScore == null) return "NOT_SCOREABLE";
        return "NOT_SELECTED_FOR_POOL";
    }

    private String servedRiskVersion(SafeRegretShadowInput input) {
        if (!input.servedResult().pool().isEmpty()) {
            return input.servedResult().pool().get(0).risk().algorithmVersion();
        }
        return input.servedRiskByPoiId().values().stream()
                .map(RiskResult::algorithmVersion).sorted().findFirst().orElse("UNKNOWN");
    }

    private String configHash(RiskPosteriorDecisionPolicy policy) {
        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        config.put("posterior", posteriorConfig);
        config.put("evidence", evidenceConfig);
        config.put("recommendation", recommendationConfig);
        config.put("features", featureConfig);
        LinkedHashMap<String, Object> tasteProjection = new LinkedHashMap<>();
        tasteProjection.put("algorithmVersion", tasteProperties.getAlgorithmVersion());
        tasteProjection.put("maxAbsoluteWeight", tasteProperties.getMaxAbsoluteWeight());
        tasteProjection.put("confidenceTargetSamples",
                tasteProperties.getConfidenceTargetSamples());
        config.put("tasteProjection", tasteProjection);
        config.put("highRiskThreshold", policy.highThreshold());
        config.put("trustedSafeThreshold", policy.trustedSafeThreshold());
        config.put("minimumDecisionConfidence", policy.minimumConfidence());
        config.put("featureSchemaVersion", properties.getFeatureSchemaVersion());
        byte[] serialized;
        try {
            ObjectMapper canonicalMapper = objectMapper.copy()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            serialized = canonicalMapper.writeValueAsBytes(config);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SafeRegret 配置序列化失败", exception);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(serialized);
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private String candidateId(Restaurant restaurant) {
        String raw = internalKey(restaurant);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String internalKey(Restaurant restaurant) {
        return internalKey(restaurant.source(), restaurant.sourcePoiId());
    }

    private String internalKey(String source, String sourcePoiId) {
        return (source == null ? "" : source) + "\u0000"
                + (sourcePoiId == null ? "" : sourcePoiId);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
