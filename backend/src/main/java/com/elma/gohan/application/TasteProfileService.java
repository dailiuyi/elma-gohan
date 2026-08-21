package com.elma.gohan.application;

import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.TasteProfile;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.infrastructure.persistence.UserPreferenceEntity;
import com.elma.gohan.infrastructure.persistence.UserPreferenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** TasteProfile 的加载、旧反馈重建和追加快照。 */
@Service
public class TasteProfileService {

    private final UserPreferenceRepository preferenceRepository;
    private final UserFeedbackRepository feedbackRepository;
    private final RestaurantRepository restaurantRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final TasteProperties properties;
    private final ObjectMapper objectMapper;

    public TasteProfileService(UserPreferenceRepository preferenceRepository,
                               UserFeedbackRepository feedbackRepository,
                               RestaurantRepository restaurantRepository,
                               RecommendationCandidateRepository candidateRepository,
                               TasteProperties properties, ObjectMapper objectMapper) {
        this.preferenceRepository = preferenceRepository;
        this.feedbackRepository = feedbackRepository;
        this.restaurantRepository = restaurantRepository;
        this.candidateRepository = candidateRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public TasteProfile load(UUID anonymousUserId) {
        TasteProfile snapshot = preferenceRepository
                .findFirstByAnonymousUserIdOrderByCreatedAtDesc(anonymousUserId)
                .map(this::parseV2).orElse(null);
        if (snapshot != null) return snapshot;

        TasteProfile rebuilt = TasteProfile.empty();
        for (var feedback : feedbackRepository
                .findByAnonymousUserIdOrderByCreatedAtAsc(anonymousUserId)) {
            Restaurant restaurant = restaurantRepository.findById(feedback.getRestaurantId())
                    .map(this::toDomain).orElse(null);
            if (restaurant == null) continue;
            int distance = candidateRepository.findByRecommendationLogIdAndRestaurantId(
                    feedback.getRecommendationLogId(), feedback.getRestaurantId())
                    .map(candidate -> candidate.getDistanceMeters()).orElse(0);
            rebuilt = rebuilt.apply(restaurant, distance, feedback.getResult(),
                    feedback.getCreatedAt(), properties);
        }
        if (rebuilt.feedbackCount() > 0) save(anonymousUserId, rebuilt);
        return rebuilt;
    }

    public TasteProfile update(UUID anonymousUserId, TasteProfile current, Restaurant restaurant,
                               int distanceMeters, String result, LocalDateTime occurredAt) {
        TasteProfile updated = current.apply(restaurant, distanceMeters, result,
                occurredAt, properties);
        save(anonymousUserId, updated);
        return updated;
    }

    private TasteProfile parseV2(UserPreferenceEntity entity) {
        try {
            JsonNode root = objectMapper.readTree(entity.getPreferenceJson());
            if (root.path("schemaVersion").asInt(-1) != TasteProfile.SCHEMA_VERSION) return null;
            return objectMapper.treeToValue(root, TasteProfile.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void save(UUID userId, TasteProfile profile) {
        try {
            preferenceRepository.save(new UserPreferenceEntity(UUID.randomUUID(), userId,
                    objectMapper.writeValueAsString(profile),
                    profile.updatedAt() == null ? LocalDateTime.now() : profile.updatedAt()));
        } catch (Exception exception) {
            throw new IllegalStateException("TasteProfile 序列化失败", exception);
        }
    }

    private Restaurant toDomain(RestaurantEntity entity) {
        return new Restaurant(entity.getId(), entity.getSource(), entity.getSourcePoiId(),
                entity.getName(), entity.getLatitude(), entity.getLongitude(), 0,
                entity.getCategoryCode(), entity.getCategoryLabel(), entity.getRating(),
                entity.getReviewCount(), entity.getAveragePrice(), entity.getBusinessStatus(),
                entity.getOpeningHours(), entity.getAddress(), entity.getTelephone(),
                entity.getDataCompleteness() == null
                ? DataCompleteness.MINIMAL : entity.getDataCompleteness(),
                entity.getCategoryConfidence());
    }
}
