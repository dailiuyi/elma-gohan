package com.elma.gohan.application;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.controller.api.FeedbackResponse;
import com.elma.gohan.controller.api.RecommendationResponse;
import com.elma.gohan.controller.api.RestaurantSummary;
import com.elma.gohan.controller.api.RiskAssessment;
import com.elma.gohan.controller.api.EvidenceSummaryResponse;
import com.elma.gohan.controller.api.SubmitFeedbackRequest;
import com.elma.gohan.controller.api.PersonalizationResponse;
import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.domain.recommendation.PersonalizationSnapshot;
import com.elma.gohan.domain.recommendation.ScoreBreakdown;
import com.elma.gohan.domain.recommendation.SelectionMode;
import com.elma.gohan.domain.recommendation.RecommendationEngine;
import com.elma.gohan.domain.recommendation.HardFilter;
import com.elma.gohan.domain.recommendation.RecommendationResult;
import com.elma.gohan.domain.recommendation.RestaurantCandidate;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskEngine;
import com.elma.gohan.domain.risk.RiskFactors;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.RiskResultEntity;
import com.elma.gohan.infrastructure.persistence.RiskResultRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackEntity;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceSummary;
import com.elma.gohan.provider.poi.PoiProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 推荐编排:POI -> 硬过滤 -> Evidence -> 风险 -> 排序 -> 候选池落库 -> reroll -> 反馈画像。
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final Set<Integer> ALLOWED_RADIUS = Set.of(500, 1000, 2000, 3000);
    private static final int MAX_ALTERNATIVES = 5;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PoiProvider poiProvider;
    private final EvidenceAggregator evidenceAggregator;
    private final RiskEngine riskEngine;
    private final RecommendationEngine recommendationEngine;
    private final HardFilter hardFilter;
    private final TasteProfileService tasteProfileService;
    private final FlavorFeatureService flavorFeatureService;
    private final UserFoodHistoryService foodHistoryService;
    private final BehaviorService behaviorService;
    private final RecommendationProperties recommendationProperties;
    private final RiskProperties riskProperties;
    private final RestaurantRepository restaurantRepository;
    private final RiskResultRepository riskResultRepository;
    private final RecommendationLogRepository logRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final UserFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;

    public RecommendationService(PoiProvider poiProvider, EvidenceAggregator evidenceAggregator,
                                 RiskEngine riskEngine, RecommendationEngine recommendationEngine,
                                 HardFilter hardFilter, TasteProfileService tasteProfileService,
                                 FlavorFeatureService flavorFeatureService,
                                 UserFoodHistoryService foodHistoryService,
                                 BehaviorService behaviorService,
                                 RecommendationProperties recommendationProperties,
                                 RiskProperties riskProperties,
                                 RestaurantRepository restaurantRepository,
                                 RiskResultRepository riskResultRepository,
                                 RecommendationLogRepository logRepository,
                                 RecommendationCandidateRepository candidateRepository,
                                 UserFeedbackRepository feedbackRepository,
                                 ObjectMapper objectMapper) {
        this.poiProvider = poiProvider;
        this.evidenceAggregator = evidenceAggregator;
        this.riskEngine = riskEngine;
        this.recommendationEngine = recommendationEngine;
        this.hardFilter = hardFilter;
        this.tasteProfileService = tasteProfileService;
        this.flavorFeatureService = flavorFeatureService;
        this.foodHistoryService = foodHistoryService;
        this.behaviorService = behaviorService;
        this.recommendationProperties = recommendationProperties;
        this.riskProperties = riskProperties;
        this.restaurantRepository = restaurantRepository;
        this.riskResultRepository = riskResultRepository;
        this.logRepository = logRepository;
        this.candidateRepository = candidateRepository;
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecommendationResponse create(UUID anonymousUserId, CreateRecommendationRequest request) {
        if (request.radius() != null && !ALLOWED_RADIUS.contains(request.radius())) {
            throw new ValidationFailedException("radius", "只能是 500、1000、2000 或 3000");
        }
        int radius = request.radius() == null ? 1000 : request.radius();
        if (request.minDistance() != null && request.minDistance() >= radius) {
            throw new ValidationFailedException("minDistance", "必须小于搜索半径");
        }
        if (request.minBudget() != null && request.maxBudget() != null
                && request.minBudget() >= request.maxBudget()) {
            throw new ValidationFailedException("minBudget", "必须小于最高预算");
        }
        if (request.dislikes() != null && request.dislikes().stream().distinct().count()
                != request.dislikes().size()) {
            throw new ValidationFailedException("dislikes", "不能有重复项");
        }
        SearchCondition condition = new SearchCondition(
                request.minDistance(), radius, request.minBudget(), request.maxBudget(),
                request.category() == null ? SearchCondition.CATEGORY_MEAL : request.category(),
                request.dislikes() == null ? List.of() : request.dislikes());

        List<Restaurant> pois = poiProvider.nearby(
                new Location(request.latitude(), request.longitude()), condition);
        List<Restaurant> eligible = hardFilter.filter(pois, condition);
        log.info("POI 推荐过滤汇总: mappedCount={}, hardEligibleCount={}, requestedCategory={}",
                pois.size(), eligible.size(), condition.category());
        if (eligible.isEmpty()) {
            throw new NoRecommendationAvailableException("附近暂时没有符合条件的餐厅,请放宽距离或预算");
        }

        Map<String, Double> priceBaselines = PriceBaselineCalculator.byCategoryGroup(
                eligible, riskProperties.getPriceAnomalyMinPoolSize());
        Map<String, EvidenceBundle> evidence = evidenceAggregator.collect(eligible,
                new Location(request.latitude(), request.longitude()), radius, priceBaselines);
        Map<String, RiskResult> risks = riskEngine.evaluateAllBundles(eligible, evidence);

        LocalDateTime now = LocalDateTime.now(ZONE);
        var tasteProfile = tasteProfileService.load(anonymousUserId);
        var flavorFeatures = flavorFeatureService.loadForCandidates(anonymousUserId, eligible);
        var foodHistory = foodHistoryService.load(anonymousUserId, now);
        long randomSeed;
        do {
            randomSeed = ThreadLocalRandom.current().nextLong();
        } while (randomSeed == 0L);
        RecommendationResult result = recommendationEngine.recommend(
                eligible, risks, new com.elma.gohan.domain.recommendation.UserPreference(
                        condition, tasteProfile, flavorFeatures, foodHistory), randomSeed);
        if (result.pool().isEmpty()) {
            throw new NoRecommendationAvailableException("附近暂时没有符合条件的餐厅,请放宽距离或预算");
        }

        List<RestaurantCandidate> pool = result.pool();
        UUID logId = UUID.randomUUID();

        // upsert restaurant,同时把内部 id 回填到候选
        List<RestaurantCandidate> persisted = new java.util.ArrayList<>(pool.size());
        for (RestaurantCandidate candidate : pool) {
            Restaurant saved = upsertRestaurant(candidate.restaurant(), now);
            riskResultRepository.save(new RiskResultEntity(
                    UUID.randomUUID(), saved.id(), candidate.risk().riskScore(),
                    candidate.risk().riskLevel().name(), candidate.risk().confidence(),
                    toJson(candidate.risk().factors()), evidenceJson(candidate.risk()),
                    toJson(candidate.risk().reasons()),
                    candidate.risk().algorithmVersion(), now));
            persisted.add(new RestaurantCandidate(saved, candidate.risk(),
                    candidate.lowRegretScore(), candidate.reasons(), candidate.personalization()));
        }

        RestaurantCandidate first = persisted.get(0);
        Map<String, Object> conditionSnapshot = new java.util.LinkedHashMap<>();
        conditionSnapshot.put("latitude", request.latitude());
        conditionSnapshot.put("longitude", request.longitude());
        conditionSnapshot.put("minDistance", request.minDistance());
        conditionSnapshot.put("radius", radius);
        conditionSnapshot.put("minBudget", request.minBudget());
        conditionSnapshot.put("maxBudget", request.maxBudget());
        conditionSnapshot.put("category", condition.category());
        conditionSnapshot.put("dislikes", condition.dislikes());
        RecommendationLogEntity recommendationLog = logRepository.save(new RecommendationLogEntity(
                logId, anonymousUserId, toJson(conditionSnapshot),
                persisted.size(), first.restaurant().id(), first.restaurant().id(),
                first.risk().riskScore(), first.lowRegretScore(),
                first.risk().algorithmVersion(), result.algorithmVersion(),
                first.personalization().algorithmVersion(),
                first.personalization().selectionMode().name(), result.randomSeed(),
                toJson(result.selectionSnapshot()), now));

        RecommendationCandidateEntity firstEntity = null;
        for (int i = 0; i < persisted.size(); i++) {
            RestaurantCandidate candidate = persisted.get(i);
            RecommendationCandidateEntity candidateEntity = candidateRepository.save(
                    new RecommendationCandidateEntity(
                    UUID.randomUUID(), logId, candidate.restaurant().id(), i + 1,
                    candidate.restaurant().distanceMeters(),
                    candidate.risk().riskScore(), candidate.risk().riskLevel().name(),
                    candidate.risk().confidence(), toJson(candidate.risk().factors()),
                    evidenceJson(candidate.risk()),
                    toJson(candidate.risk().reasons()), candidate.risk().algorithmVersion(),
                    candidate.lowRegretScore(), toJson(candidate.reasons()),
                    candidate.personalization().tasteMatchScore(),
                    candidate.personalization().confidence(),
                    toJson(candidate.personalization().scoreBreakdown()),
                    toJson(candidate.personalization().reasons()),
                    candidate.personalization().selectionMode().name(), i == 0));
            if (i == 0) firstEntity = candidateEntity;
        }

        behaviorService.recordRecommended(anonymousUserId, recommendationLog,
                java.util.Objects.requireNonNull(firstEntity), now);

        return toResponse(logId, persisted.get(0), persisted.size() - 1);
    }

    @Transactional
    public RecommendationResponse reroll(UUID anonymousUserId, UUID recommendationId) {
        RecommendationLogEntity log = findLog(anonymousUserId, recommendationId);
        List<RecommendationCandidateEntity> candidates =
                candidateRepository.findByRecommendationLogIdOrderBySlotAsc(recommendationId);
        RecommendationCandidateEntity previous = candidateRepository
                .findByRecommendationLogIdAndRestaurantId(recommendationId,
                        log.getCurrentRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));

        RecommendationCandidateEntity target = null;
        int remaining = 0;
        for (RecommendationCandidateEntity c : candidates) {
            if (!c.isShown()) {
                if (target == null) {
                    target = c;
                    c.markShown();
                } else {
                    remaining++;
                }
            }
        }
        if (target == null) {
            // 候选耗尽:回到初始推荐,不再生成新候选
            target = candidates.get(0);
            remaining = 0;
            return toResponse(log.getId(), toView(log.getId(), target), remaining);
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        behaviorService.recordReroll(anonymousUserId, log, previous, now);
        log.updateCurrent(target.getRestaurantId());
        behaviorService.recordRecommended(anonymousUserId, log, target, now);

        return toResponse(log.getId(), toView(log.getId(), target), remaining);
    }

    @Transactional
    public FeedbackResponse submitFeedback(UUID anonymousUserId, UUID recommendationId,
                                           SubmitFeedbackRequest request) {
        RecommendationLogEntity log = findLog(anonymousUserId, recommendationId);
        LocalDateTime now = LocalDateTime.now(ZONE);
        RestaurantEntity restaurantEntity = restaurantRepository.findById(log.getCurrentRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        RecommendationCandidateEntity currentCandidate = candidateRepository
                .findByRecommendationLogIdAndRestaurantId(log.getId(), log.getCurrentRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        if (request.flavorTags().stream().distinct().count() != request.flavorTags().size()) {
            throw new ValidationFailedException("flavorTags", "不能有重复项");
        }
        UserFeedbackEntity existing = feedbackRepository
                .findByRecommendationLogIdAndRestaurantId(log.getId(), log.getCurrentRestaurantId())
                .orElse(null);
        if (existing != null) {
            if (existing.getResult().equals(request.result().name())
                    && java.util.Set.copyOf(existing.flavorTags(objectMapper))
                    .equals(java.util.Set.copyOf(request.flavorTags()))) {
                return new FeedbackResponse(existing.getId().toString(), log.getId().toString(),
                        existing.getRestaurantId().toString(), existing.getResult(),
                        existing.getCreatedAt().atZone(ZONE).toString());
            }
            throw new FeedbackAlreadyRecordedException("这家餐厅的反馈已经记录");
        }
        var currentProfile = tasteProfileService.load(anonymousUserId);
        UserFeedbackEntity feedback = feedbackRepository.save(new UserFeedbackEntity(
                UUID.randomUUID(), log.getId(), log.getCurrentRestaurantId(), anonymousUserId,
                request.result().name(), toJson(request.flavorTags()), now));
        flavorFeatureService.record(anonymousUserId, restaurantEntity, request.flavorTags(),
                request.result().name(), now);
        tasteProfileService.updateFeedback(anonymousUserId, currentProfile,
                toDomain(restaurantEntity, currentCandidate.getDistanceMeters()),
                currentCandidate.getDistanceMeters(), request.result().name(),
                request.flavorTags(), now);
        foodHistoryService.record(anonymousUserId, log.getId(), restaurantEntity,
                request.result().name(), now);
        behaviorService.recordFeedback(anonymousUserId, log, currentCandidate,
                BehaviorType.valueOf(request.result().name()), now);
        return new FeedbackResponse(
                feedback.getId().toString(), log.getId().toString(),
                feedback.getRestaurantId().toString(), feedback.getResult(),
                now.atZone(ZONE).toString());
    }

    private RecommendationLogEntity findLog(UUID anonymousUserId, UUID recommendationId) {
        return logRepository.findByIdAndAnonymousUserId(recommendationId, anonymousUserId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
    }

    private Restaurant upsertRestaurant(Restaurant r, LocalDateTime now) {
        RestaurantEntity entity = restaurantRepository
                .findBySourceAndSourcePoiId(r.source(), r.sourcePoiId())
                .orElseGet(() -> new RestaurantEntity(UUID.randomUUID(), r.source(), r.sourcePoiId(),
                        r.name(), r.latitude(), r.longitude(), r.categoryCode(), r.categoryLabel(),
                        r.rating(), r.reviewCount(), r.averagePrice(), r.businessStatus(),
                        r.openingHours(), r.address(), r.telephone(), r.dataCompleteness(),
                        r.categoryConfidence(), now, now));
        RestaurantEntity updated = new RestaurantEntity(
                entity.getId(), entity.getSource(), entity.getSourcePoiId(),
                r.name(), r.latitude(), r.longitude(), r.categoryCode(), r.categoryLabel(),
                r.rating(), r.reviewCount(), r.averagePrice(), r.businessStatus(),
                r.openingHours(), r.address(), r.telephone(), r.dataCompleteness(),
                r.categoryConfidence(),
                entity.getCreatedAt(), now);
        return toDomain(restaurantRepository.save(updated), r.distanceMeters());
    }

    private Restaurant toDomain(RestaurantEntity e, int distanceMeters) {
        return new Restaurant(e.getId(), e.getSource(), e.getSourcePoiId(), e.getName(),
                e.getLatitude(), e.getLongitude(), distanceMeters, e.getCategoryCode(),
                e.getCategoryLabel(), e.getRating(), e.getReviewCount(), e.getAveragePrice(),
                e.getBusinessStatus(), e.getOpeningHours(), e.getAddress(), e.getTelephone(),
                e.getDataCompleteness() == null ? DataCompleteness.MINIMAL : e.getDataCompleteness(),
                e.getCategoryConfidence());
    }

    /** reroll 时从候选快照 + restaurant 表重建候选视图。 */
    private RestaurantCandidate toView(UUID logId, RecommendationCandidateEntity c) {
        RestaurantEntity e = restaurantRepository.findById(c.getRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        Restaurant restaurant = toDomain(e, c.getDistanceMeters());
        RiskResult risk = new RiskResult(c.getRiskScore(), RiskLevel.valueOf(c.getRiskLevel()),
                c.getRiskConfidence(), fromFactorsJson(c.getRiskFactorsJson()),
                fromJson(c.getRiskReasonsJson()), c.getRiskAlgorithmVersion(),
                fromEvidenceSummaryJson(c.getEvidenceSummaryJson()));
        PersonalizationSnapshot personalization = new PersonalizationSnapshot(
                c.getTasteMatchScore(), c.getTasteConfidence(),
                SelectionMode.valueOf(c.getSelectionMode()),
                fromJson(c.getPersonalizationReasonsJson()), "taste-v0.1",
                fromScoreBreakdownJson(c.getScoreBreakdownJson()));
        return new RestaurantCandidate(restaurant, risk, c.getLowRegretScore(),
                fromJson(c.getReasonsJson()), personalization);
    }

    private RecommendationResponse toResponse(UUID logId, RestaurantCandidate candidate,
                                              int alternativesRemaining) {
        Restaurant r = candidate.restaurant();
        int walkingMinutes = Math.max(1, (int) Math.ceil(
                r.distanceMeters() / (double) recommendationProperties.getWalkingSpeedMetersPerMinute()));
        return new RecommendationResponse(
                logId.toString(),
                new RestaurantSummary(
                        r.id().toString(), r.name(), r.latitude(), r.longitude(), r.address(),
                        new RestaurantSummary.Category(r.categoryCode(), r.categoryLabel()),
                        r.distanceMeters(), walkingMinutes, r.averagePrice(), r.rating(),
                        r.businessStatus().name()),
                new RiskAssessment(candidate.risk().riskScore(), candidate.risk().riskLevel().name(),
                        candidate.risk().confidence(),
                        candidate.risk().reasons(), candidate.risk().algorithmVersion()),
                EvidenceSummaryResponse.from(candidate.risk().evidenceSummary()),
                new PersonalizationResponse(candidate.personalization().tasteMatchScore(),
                        candidate.personalization().confidence(),
                        candidate.personalization().selectionMode().name(),
                        candidate.personalization().reasons(),
                        candidate.personalization().algorithmVersion()),
                candidate.reasons(),
                Math.min(MAX_ALTERNATIVES, Math.max(0, alternativesRemaining)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    private RiskFactors fromFactorsJson(String json) {
        try {
            return objectMapper.readValue(json, RiskFactors.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("风险因子反序列化失败", e);
        }
    }

    private ScoreBreakdown fromScoreBreakdownJson(String json) {
        try {
            return objectMapper.readValue(json, ScoreBreakdown.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("个性化分项反序列化失败", e);
        }
    }

    private String evidenceJson(RiskResult risk) {
        return risk.evidenceSummary() == null ? "{}" : toJson(risk.evidenceSummary());
    }

    private EvidenceSummary fromEvidenceSummaryJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            return objectMapper.readValue(json, EvidenceSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Evidence 摘要反序列化失败", e);
        }
    }
}
