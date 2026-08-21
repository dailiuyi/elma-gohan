package com.elma.gohan.application;

import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.domain.recommendation.FlavorTag;
import com.elma.gohan.domain.recommendation.ImplicitAccumulator;
import com.elma.gohan.domain.recommendation.TasteProfile;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.infrastructure.persistence.UserPreferenceEntity;
import com.elma.gohan.infrastructure.persistence.UserPreferenceRepository;
import com.elma.gohan.infrastructure.persistence.UserTasteProfileEntity;
import com.elma.gohan.infrastructure.persistence.UserTasteProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** TasteProfile v0.1 的正式持久化、旧快照恢复与时间衰减。 */
@Service
public class TasteProfileService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final UserTasteProfileRepository profileRepository;
    private final UserPreferenceRepository legacyRepository;
    private final UserFeedbackRepository feedbackRepository;
    private final RestaurantRepository restaurantRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final TasteProperties properties;
    private final ObjectMapper objectMapper;

    public TasteProfileService(UserTasteProfileRepository profileRepository,
            UserPreferenceRepository legacyRepository, UserFeedbackRepository feedbackRepository,
            RestaurantRepository restaurantRepository,
            RecommendationCandidateRepository candidateRepository,
            TasteProperties properties, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.legacyRepository = legacyRepository;
        this.feedbackRepository = feedbackRepository;
        this.restaurantRepository = restaurantRepository;
        this.candidateRepository = candidateRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public TasteProfile load(UUID userId) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        TasteProfile profile = profileRepository.findById(userId).map(this::toDomain).orElse(null);
        if (profile == null) {
            profile = legacyRepository.findFirstByAnonymousUserIdOrderByCreatedAtDesc(userId)
                    .map(this::parseLegacy).orElse(null);
        }
        if (profile == null) profile = rebuildFeedback(userId, now);
        if (profile == null) return TasteProfile.empty(now);
        return profile.decayedTo(now, properties);
    }

    public TasteProfile update(UUID userId, TasteProfile current, Restaurant restaurant,
            int distanceMeters, String result, LocalDateTime occurredAt) {
        return updateFeedback(userId, current, restaurant, distanceMeters, result,
                java.util.List.of(), occurredAt);
    }

    public TasteProfile updateFeedback(UUID userId, TasteProfile current, Restaurant restaurant,
            int distanceMeters, String result, Collection<FlavorTag> tags,
            LocalDateTime occurredAt) {
        TasteProfile updated = (current == null ? load(userId) : current)
                .applyFeedback(restaurant, distanceMeters, result, tags, occurredAt, properties);
        save(userId, updated);
        return updated;
    }

    public TasteProfile updateImplicit(UUID userId, Restaurant restaurant, int distanceMeters,
            Collection<FlavorTag> tags, BehaviorType type, LocalDateTime occurredAt) {
        TasteProfile updated = load(userId).applyImplicit(restaurant, distanceMeters, tags,
                type, occurredAt, properties);
        save(userId, updated);
        return updated;
    }

    private TasteProfile rebuildFeedback(UUID userId, LocalDateTime now) {
        TasteProfile rebuilt = TasteProfile.empty(now);
        boolean found = false;
        for (var feedback : feedbackRepository.findByAnonymousUserIdOrderByCreatedAtAsc(userId)) {
            Restaurant restaurant = restaurantRepository.findById(feedback.getRestaurantId())
                    .map(this::toDomain).orElse(null);
            if (restaurant == null) continue;
            int distance = candidateRepository.findByRecommendationLogIdAndRestaurantId(
                    feedback.getRecommendationLogId(), feedback.getRestaurantId())
                    .map(candidate -> candidate.getDistanceMeters()).orElse(0);
            rebuilt = rebuilt.applyFeedback(restaurant, distance, feedback.getResult(),
                    feedback.flavorTags(objectMapper), feedback.getCreatedAt(), properties);
            found = true;
        }
        if (found) save(userId, rebuilt);
        return found ? rebuilt : null;
    }

    private TasteProfile parseLegacy(UserPreferenceEntity entity) {
        try {
            JsonNode root = objectMapper.readTree(entity.getPreferenceJson());
            if (root.path("schemaVersion").asInt(-1) != 2) return null;
            return new TasteProfile(2,
                    objectMapper.convertValue(root.path("categoryWeights"), new TypeReference<>() { }),
                    objectMapper.convertValue(root.path("priceBandWeights"), new TypeReference<>() { }),
                    objectMapper.convertValue(root.path("distanceBandWeights"), new TypeReference<>() { }),
                    root.path("feedbackCount").asInt(0), entity.getCreatedAt());
        } catch (Exception ignored) { return null; }
    }

    private TasteProfile toDomain(UserTasteProfileEntity entity) {
        try {
            return new TasteProfile(entity.getSchemaVersion(),
                    readDoubles(entity.getCategoryWeightsJson()),
                    readDoubles(entity.getFlavorWeightsJson()),
                    readDoubles(entity.getPriceWeightsJson()),
                    readDoubles(entity.getDistanceWeightsJson()),
                    objectMapper.readValue(entity.getImplicitAccumulatorsJson(), new TypeReference<Map<String, ImplicitAccumulator>>() { }),
                    entity.getExplicitFeedbackCount(), entity.getImplicitBehaviorCount(),
                    entity.getLastDecayedAt(), entity.getLastFeedbackAt(), entity.getUpdatedAt());
        } catch (Exception exception) {
            throw new IllegalStateException("TasteProfile 反序列化失败", exception);
        }
    }

    private Map<String, Double> readDoubles(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() { });
    }

    private void save(UUID userId, TasteProfile profile) {
        try {
            String category = objectMapper.writeValueAsString(profile.categoryWeights());
            String flavor = objectMapper.writeValueAsString(profile.flavorWeights());
            String price = objectMapper.writeValueAsString(profile.priceBandWeights());
            String distance = objectMapper.writeValueAsString(profile.distanceBandWeights());
            String accumulators = objectMapper.writeValueAsString(profile.implicitAccumulators());
            UserTasteProfileEntity entity = profileRepository.findById(userId).orElse(null);
            if (entity == null) {
                entity = new UserTasteProfileEntity(userId, TasteProfile.SCHEMA_VERSION,
                        properties.getAlgorithmVersion(), category, flavor, price, distance,
                        accumulators, profile.explicitFeedbackCount(),
                        profile.implicitBehaviorCount(), profile.lastDecayedAt(),
                        profile.lastFeedbackAt(), profile.updatedAt());
            } else {
                entity.replace(TasteProfile.SCHEMA_VERSION, properties.getAlgorithmVersion(),
                        category, flavor, price, distance, accumulators,
                        profile.explicitFeedbackCount(), profile.implicitBehaviorCount(),
                        profile.lastDecayedAt(), profile.lastFeedbackAt(), profile.updatedAt());
            }
            profileRepository.save(entity);
        } catch (Exception exception) {
            throw new IllegalStateException("TasteProfile 序列化失败", exception);
        }
    }

    private Restaurant toDomain(RestaurantEntity e) {
        return new Restaurant(e.getId(), e.getSource(), e.getSourcePoiId(), e.getName(),
                e.getLatitude(), e.getLongitude(), 0, e.getCategoryCode(), e.getCategoryLabel(),
                e.getRating(), e.getReviewCount(), e.getAveragePrice(), e.getBusinessStatus(),
                e.getOpeningHours(), e.getAddress(), e.getTelephone(),
                e.getDataCompleteness() == null ? DataCompleteness.MINIMAL : e.getDataCompleteness(),
                e.getCategoryConfidence());
    }
}
