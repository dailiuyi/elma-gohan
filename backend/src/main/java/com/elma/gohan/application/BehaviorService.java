package com.elma.gohan.application;

import com.elma.gohan.controller.api.BehaviorResponse;
import com.elma.gohan.controller.api.SubmitBehaviorRequest;
import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.domain.recommendation.FlavorTag;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserBehaviorEntity;
import com.elma.gohan.infrastructure.persistence.UserBehaviorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 记录用户行为；只有可独立解释的信号才同步到长期口味画像。 */
@Service
public class BehaviorService {
    /** 行为写入结果及是否为首次创建。 */
    public record Recorded(BehaviorResponse response, boolean created) { }
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final RecommendationLogRepository logRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final RestaurantRepository restaurantRepository;
    private final UserBehaviorRepository behaviorRepository;
    private final FlavorFeatureService flavorFeatureService;
    private final TasteProfileService tasteProfileService;
    private final ObjectMapper objectMapper;

    public BehaviorService(RecommendationLogRepository logRepository,
            RecommendationCandidateRepository candidateRepository,
            RestaurantRepository restaurantRepository, UserBehaviorRepository behaviorRepository,
            FlavorFeatureService flavorFeatureService, TasteProfileService tasteProfileService,
            ObjectMapper objectMapper) {
        this.logRepository = logRepository; this.candidateRepository = candidateRepository;
        this.restaurantRepository = restaurantRepository; this.behaviorRepository = behaviorRepository;
        this.flavorFeatureService = flavorFeatureService; this.tasteProfileService = tasteProfileService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    /** 记录客户端行为；相同事件 ID 会幂等返回。 */
    public Recorded recordClient(UUID userId, UUID recommendationId, SubmitBehaviorRequest request) {
        if (!request.type().clientWritable()) {
            throw new ValidationFailedException("type", "客户端只能提交 ACCEPT、NAVIGATE 或 SKIP");
        }
        RecommendationLogEntity log = findLog(userId, recommendationId);
        UserBehaviorEntity existingById = behaviorRepository.findById(request.eventId()).orElse(null);
        if (existingById != null) {
            if (!existingById.getAnonymousUserId().equals(userId)
                    || !existingById.getRecommendationLogId().equals(recommendationId)
                    || !existingById.getRestaurantId().equals(request.restaurantId())
                    || !existingById.getBehaviorType().equals(request.type().name())) {
                throw new ValidationFailedException("eventId", "已被其他行为使用");
            }
            return new Recorded(toResponse(existingById, true), false);
        }
        RecommendationCandidateEntity candidate = candidateRepository
                .findByRecommendationLogIdAndRestaurantId(recommendationId, request.restaurantId())
                .filter(RecommendationCandidateEntity::isShown)
                .orElseThrow(() -> new RecommendationNotFoundException("餐厅未在当前会话中展示"));
        UserBehaviorEntity sameType = behaviorRepository
                .findByRecommendationLogIdAndRestaurantIdAndBehaviorType(recommendationId,
                        request.restaurantId(), request.type().name()).orElse(null);
        if (sameType != null) return new Recorded(toResponse(sameType, true), false);
        LocalDateTime now = LocalDateTime.now(ZONE);
        UserBehaviorEntity saved = persist(request.eventId(), userId, log, candidate,
                request.type(), "CLIENT", now);
        learnImplicit(userId, candidate, request.type(), now);
        return new Recorded(toResponse(saved, false), true);
    }

    /** 记录服务端产生的推荐展示事件。 */
    public void recordRecommended(UUID userId, RecommendationLogEntity log,
            RecommendationCandidateEntity candidate, LocalDateTime now) {
        recordServer(userId, log, candidate, BehaviorType.RECOMMENDED, now);
    }
    /** 记录换一家事件。 */
    public void recordReroll(UUID userId, RecommendationLogEntity log,
            RecommendationCandidateEntity candidate, LocalDateTime now) {
        recordServer(userId, log, candidate, BehaviorType.REROLL, now);
    }
    /** 将显式反馈同步为行为记录。 */
    public void recordFeedback(UUID userId, RecommendationLogEntity log,
            RecommendationCandidateEntity candidate, BehaviorType type, LocalDateTime now) {
        recordServer(userId, log, candidate, type, now);
    }

    private void recordServer(UUID userId, RecommendationLogEntity log,
            RecommendationCandidateEntity candidate, BehaviorType type, LocalDateTime now) {
        var existing = behaviorRepository.findByRecommendationLogIdAndRestaurantIdAndBehaviorType(
                log.getId(), candidate.getRestaurantId(), type.name());
        if (existing.isPresent()) return;
        UUID id = UUID.nameUUIDFromBytes((log.getId() + ":" + candidate.getRestaurantId() + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
        persist(id, userId, log, candidate, type, "SERVER", now);
        // 单独一次 REROLL 无法说明用户拒绝的是品类、价格还是距离。
        // 它只保留为会话事件，等待后续 A -> B 的选择形成差分证据。
    }

    private UserBehaviorEntity persist(UUID id, UUID userId, RecommendationLogEntity log,
            RecommendationCandidateEntity candidate, BehaviorType type, String source,
            LocalDateTime now) {
        RestaurantEntity restaurant = restaurantRepository.findById(candidate.getRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        try {
            String features = objectMapper.writeValueAsString(Map.of(
                    "category", restaurant.getCategoryCode(),
                    "price", restaurant.getAveragePrice() == null ? "UNKNOWN" : restaurant.getAveragePrice(),
                    "distance", candidate.getDistanceMeters()));
            return behaviorRepository.save(new UserBehaviorEntity(id, userId, log.getId(),
                    restaurant.getId(), type.name(), source, features,
                    log.getRiskAlgorithmVersion(), log.getRecommendationAlgorithmVersion(),
                    log.getTasteAlgorithmVersion(), now));
        } catch (Exception exception) {
            throw new IllegalStateException("行为快照保存失败", exception);
        }
    }

    private void learnImplicit(UUID userId, RecommendationCandidateEntity candidate,
            BehaviorType type, LocalDateTime now) {
        RestaurantEntity entity = restaurantRepository.findById(candidate.getRestaurantId())
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        Set<FlavorTag> flavors = flavorFeatureService.loadForRestaurant(userId, entity.getId());
        tasteProfileService.updateImplicit(userId, toDomain(entity, candidate.getDistanceMeters()),
                candidate.getDistanceMeters(), flavors, type, now);
    }

    private RecommendationLogEntity findLog(UUID userId, UUID recommendationId) {
        return logRepository.findByIdAndAnonymousUserId(recommendationId, userId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
    }
    private BehaviorResponse toResponse(UserBehaviorEntity entity, boolean deduplicated) {
        return new BehaviorResponse(entity.getId().toString(),
                entity.getRecommendationLogId().toString(), entity.getRestaurantId().toString(),
                entity.getBehaviorType(), entity.getOccurredAt().atZone(ZONE).toString(), deduplicated);
    }
    private Restaurant toDomain(RestaurantEntity e, int distance) {
        return new Restaurant(e.getId(), e.getSource(), e.getSourcePoiId(), e.getName(),
                e.getLatitude(), e.getLongitude(), distance, e.getCategoryCode(), e.getCategoryLabel(),
                e.getRating(), e.getReviewCount(), e.getAveragePrice(), e.getBusinessStatus(),
                e.getOpeningHours(), e.getAddress(), e.getTelephone(),
                e.getDataCompleteness() == null ? DataCompleteness.MINIMAL : e.getDataCompleteness(),
                e.getCategoryConfidence());
    }
}
