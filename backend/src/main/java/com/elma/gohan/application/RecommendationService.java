package com.elma.gohan.application;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.controller.api.FeedbackResponse;
import com.elma.gohan.controller.api.RecommendationResponse;
import com.elma.gohan.controller.api.SearchNotice;
import com.elma.gohan.controller.api.SubmitFeedbackRequest;
import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.domain.recommendation.RecommendationEngine;
import com.elma.gohan.domain.recommendation.HardFilter;
import com.elma.gohan.domain.recommendation.RecommendationResult;
import com.elma.gohan.domain.recommendation.RestaurantCandidate;
import com.elma.gohan.domain.recommendation.UserPreference;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskEngine;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackEntity;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.poi.PoiProvider;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import com.elma.gohan.provider.poi.PoiSearchResult;
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
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SEARCH_INCOMPLETE_CODE = "SEARCH_INCOMPLETE";
    private static final String SEARCH_INCOMPLETE_ERROR =
            "附近餐馆已经很多，本次还没完整翻到你选择的距离范围。试试更近一点，或选择更具体的品类。";
    private static final String SEARCH_INCOMPLETE_NOTICE =
            "这一区间的餐厅还没搜完，先从已经找到的合适选项里为你选了一家。";

    private final PoiProvider poiProvider;
    private final EvidenceAggregator evidenceAggregator;
    private final RiskEngine riskEngine;
    private final RecommendationEngine recommendationEngine;
    private final HardFilter hardFilter;
    private final TasteProfileService tasteProfileService;
    private final FlavorFeatureService flavorFeatureService;
    private final UserFoodHistoryService foodHistoryService;
    private final BehaviorService behaviorService;
    private final RiskProperties riskProperties;
    private final RestaurantRepository restaurantRepository;
    private final RecommendationLogRepository logRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final UserFeedbackRepository feedbackRepository;
    private final ObjectMapper objectMapper;
    private final RecommendationSessionWriter sessionWriter;
    private final RecommendationResponseAssembler responseAssembler;
    private final RecommendationSnapshotJson json;

    public RecommendationService(PoiProvider poiProvider, EvidenceAggregator evidenceAggregator,
                                 RiskEngine riskEngine, RecommendationEngine recommendationEngine,
                                 HardFilter hardFilter, TasteProfileService tasteProfileService,
                                 FlavorFeatureService flavorFeatureService,
                                 UserFoodHistoryService foodHistoryService,
                                 BehaviorService behaviorService,
                                 RiskProperties riskProperties,
                                 RestaurantRepository restaurantRepository,
                                 RecommendationLogRepository logRepository,
                                 RecommendationCandidateRepository candidateRepository,
                                 UserFeedbackRepository feedbackRepository,
                                 ObjectMapper objectMapper, RecommendationSessionWriter sessionWriter,
                                 RecommendationResponseAssembler responseAssembler, RecommendationSnapshotJson json) {
        this.poiProvider = poiProvider;
        this.evidenceAggregator = evidenceAggregator;
        this.riskEngine = riskEngine;
        this.recommendationEngine = recommendationEngine;
        this.hardFilter = hardFilter;
        this.tasteProfileService = tasteProfileService;
        this.flavorFeatureService = flavorFeatureService;
        this.foodHistoryService = foodHistoryService;
        this.behaviorService = behaviorService;
        this.riskProperties = riskProperties;
        this.restaurantRepository = restaurantRepository;
        this.logRepository = logRepository;
        this.candidateRepository = candidateRepository;
        this.feedbackRepository = feedbackRepository;
        this.objectMapper = objectMapper;
        this.sessionWriter = sessionWriter;
        this.responseAssembler = responseAssembler;
        this.json = json;
    }

    /** 创建并冻结一个最多六家餐厅的推荐会话。 */
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

        PoiSearchResult recall = poiProvider.nearby(
                new Location(request.latitude(), request.longitude()), condition);
        List<Restaurant> pois = recall.restaurants();
        List<Restaurant> hardEligible = hardFilter.filter(pois, condition);
        PoiSearchDiagnostics diagnostics = recall.diagnostics();
        log.info("POI 推荐过滤汇总: providerTotalCount={}, fetchedCount={}, pagesFetched={}, "
                        + "deduplicatedCount={}, mappedCount={}, hardEligibleCount={}, truncated={}, "
                        + "completionReason={}, retryCount={}, cacheStatus={}, cacheWeightBytes={}, "
                        + "lastFetchedDistanceMeters={}, requestedCategory={}, queryTypes={}",
                diagnostics.providerTotalCount(), diagnostics.fetchedCount(),
                diagnostics.pagesFetched(), diagnostics.deduplicatedCount(),
                diagnostics.mappedCount(), hardEligible.size(), diagnostics.truncated(),
                diagnostics.completionReason(), diagnostics.retryCount(), diagnostics.cacheStatus(),
                diagnostics.estimatedCacheWeightBytes(),
                diagnostics.lastFetchedDistanceMeters(), condition.category(),
                diagnostics.queryTypes());
        SearchNotice searchNotice = diagnostics.incomplete()
                ? new SearchNotice(SEARCH_INCOMPLETE_CODE, SEARCH_INCOMPLETE_NOTICE) : null;
        if (hardEligible.isEmpty()) {
            if (diagnostics.incomplete()) {
                throw new PoiSearchIncompleteException(SEARCH_INCOMPLETE_ERROR);
            }
            throw new NoRecommendationAvailableException("附近暂时没有符合条件的餐厅,请放宽距离或预算");
        }

        Map<String, Double> priceBaselines = PriceBaselineCalculator.byCategoryGroup(
                hardEligible, riskProperties.getPriceAnomalyMinPoolSize());
        Map<String, EvidenceBundle> evidence = evidenceAggregator.collect(hardEligible,
                new Location(request.latitude(), request.longitude()), radius, priceBaselines);
        Map<String, RiskResult> risks = riskEngine.evaluateAllBundles(hardEligible, evidence);
        // exclude 只改变最终选池，不改变其他候选的价格基线、Evidence 或风险。
        // 若被排除项是唯一仍可服务的候选，则保留它，兼容旧前端“有其他合格餐厅才排除”。
        List<Restaurant> eligible = excludeRequestedRestaurant(
                hardEligible, request.excludeRestaurantId(), risks);

        LocalDateTime now = LocalDateTime.now(ZONE);
        var tasteProfile = tasteProfileService.load(anonymousUserId);
        // 画像特征同样冻结 hard-filter 全集，保证被 exclude 项仍可完整审计/重放。
        var flavorFeatures = flavorFeatureService.loadForCandidates(anonymousUserId, hardEligible);
        var foodHistory = foodHistoryService.load(anonymousUserId, now);
        UserPreference preference = new UserPreference(
                condition, tasteProfile, flavorFeatures, foodHistory);
        long randomSeed;
        do {
            randomSeed = ThreadLocalRandom.current().nextLong();
        } while (randomSeed == 0L);
        RecommendationResult result = recommendationEngine.recommend(
                eligible, risks, preference, randomSeed);
        if (result.pool().isEmpty()) {
            if (diagnostics.incomplete()) {
                throw new PoiSearchIncompleteException(SEARCH_INCOMPLETE_ERROR);
            }
            throw new NoRecommendationAvailableException("附近暂时没有符合条件的餐厅,请放宽距离或预算");
        }

        var saved = sessionWriter.save(new RecommendationSessionWriter.Creation(anonymousUserId, request,
                preference, hardEligible, eligible, evidence, risks, result, diagnostics, searchNotice, now));
        return responseAssembler.toResponse(saved.id(), saved.first(), saved.poolSize() - 1, searchNotice);
    }

    List<Restaurant> excludeRequestedRestaurant(
            List<Restaurant> candidates, String excludeRestaurantId,
            Map<String, RiskResult> risks) {
        if (excludeRestaurantId == null || excludeRestaurantId.isBlank()
                || candidates.size() <= 1) {
            return candidates;
        }
        RestaurantEntity previous = restaurantRepository.findById(UUID.fromString(excludeRestaurantId))
                .orElse(null);
        if (previous == null) return candidates;

        java.util.function.Predicate<Restaurant> isPrevious = candidate ->
                previous.getSource().equals(candidate.source())
                        && previous.getSourcePoiId().equals(candidate.sourcePoiId());
        if (candidates.stream().noneMatch(isPrevious)) return candidates;
        boolean hasOtherServable = candidates.stream()
                .filter(isPrevious.negate())
                .map(candidate -> risks.get(candidate.sourcePoiId()))
                .anyMatch(risk -> risk != null && risk.riskLevel() != RiskLevel.HIGH);
        if (!hasOtherServable) return candidates;
        return candidates.stream().filter(isPrevious.negate()).toList();
    }

    @Transactional
    /** 从冻结候选池切换到下一家，不重新计算风险和画像。 */
    public RecommendationResponse reroll(UUID anonymousUserId, UUID recommendationId) {
        RecommendationLogEntity log = findLogForUpdate(anonymousUserId, recommendationId);
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
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (target == null) {
            // 候选耗尽:回到初始推荐,不再生成新候选
            target = candidates.get(0);
            remaining = 0;
            behaviorService.recordReroll(anonymousUserId, log, previous, now);
            log.updateCurrent(target.getRestaurantId());
            return responseAssembler.toResponse(log.getId(), toView(log, target), remaining,
                    json.searchNotice(log.getRecallDiagnosticsJson()));
        }
        behaviorService.recordReroll(anonymousUserId, log, previous, now);
        log.updateCurrent(target.getRestaurantId());
        behaviorService.recordRecommended(anonymousUserId, log, target, now);

        return responseAssembler.toResponse(log.getId(), toView(log, target), remaining,
                json.searchNotice(log.getRecallDiagnosticsJson()));
    }

    @Transactional
    /** 保存显式反馈，并更新下一次推荐使用的画像与饮食历史。 */
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
                request.result().name(), json.encode(request.flavorTags()), now));
        flavorFeatureService.record(anonymousUserId, restaurantEntity, request.flavorTags(),
                request.result().name(), now);
        tasteProfileService.updateFeedback(anonymousUserId, currentProfile,
                restaurantEntity.toRestaurant(currentCandidate.getDistanceMeters()),
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

    private RecommendationLogEntity findLogForUpdate(UUID anonymousUserId,
                                                       UUID recommendationId) {
        return logRepository.findByIdAndAnonymousUserIdForUpdate(
                        recommendationId, anonymousUserId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
    }

    private RestaurantCandidate toView(RecommendationLogEntity log, RecommendationCandidateEntity candidate) {
        RestaurantEntity restaurant = restaurantRepository.findById(candidate.getRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        return responseAssembler.toView(log, candidate, restaurant);
    }
}
