package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.domain.recommendation.FlavorTag;
import com.elma.gohan.domain.recommendation.TasteProfile;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackEntity;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.infrastructure.persistence.UserPreferenceEntity;
import com.elma.gohan.infrastructure.persistence.UserPreferenceRepository;
import com.elma.gohan.infrastructure.persistence.UserTasteProfileEntity;
import com.elma.gohan.infrastructure.persistence.UserTasteProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class TasteProfileServiceTest {
    private final UserTasteProfileRepository profiles = mock(UserTasteProfileRepository.class);
    private final UserPreferenceRepository legacy = mock(UserPreferenceRepository.class);
    private final UserFeedbackRepository feedback = mock(UserFeedbackRepository.class);
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final RecommendationCandidateRepository candidates = mock(RecommendationCandidateRepository.class);
    private final TasteProperties properties = new TasteProperties();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TasteProfileService service = new TasteProfileService(profiles, legacy, feedback,
            restaurants, candidates, properties, mapper);
    private final UUID userId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void newUserGetsEmptyProfileWithoutPersistingIt() {
        TasteProfile result = service.load(userId);

        assertThat(result.categoryWeights()).isEmpty();
        assertThat(result.explicitFeedbackCount()).isZero();
        verify(profiles, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void currentProfileTakesPrecedenceAndDecaysOldWeights() {
        when(profiles.findById(userId)).thenReturn(Optional.of(entity("{\"CHINESE\":1.0}", now.minusDays(60))));

        TasteProfile result = service.load(userId);

        assertThat(result.categoryWeights().get("CHINESE")).isBetween(0.0, 0.99);
        assertThat(result.explicitFeedbackCount()).isEqualTo(2);
        verifyNoInteractions(legacy, feedback);
    }

    @Test
    void restoresVersionTwoLegacySnapshot() {
        when(legacy.findFirstByAnonymousUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(
                new UserPreferenceEntity(UUID.randomUUID(), userId, """
                        {"schemaVersion":2,"categoryWeights":{"CHINESE":0.5},
                         "priceBandWeights":{},"distanceBandWeights":{},"feedbackCount":4}
                        """, now)));

        TasteProfile result = service.load(userId);

        assertThat(result.categoryWeights()).containsEntry("CHINESE", 0.5);
        assertThat(result.explicitFeedbackCount()).isEqualTo(4);
        verifyNoInteractions(feedback);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{\"schemaVersion\":1}",
            "{\"schemaVersion\":2,\"categoryWeights\":\"invalid\"}"})
    void invalidLegacySnapshotFallsBackToFeedbackRebuild(String json) {
        when(legacy.findFirstByAnonymousUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(
                new UserPreferenceEntity(UUID.randomUUID(), userId, json, now)));

        assertThat(service.load(userId).explicitFeedbackCount()).isZero();

        verify(feedback).findByAnonymousUserIdOrderByCreatedAtAsc(userId);
    }

    @Test
    void corruptCurrentProfileFailsInsteadOfSilentlyUsingLegacyData() {
        when(profiles.findById(userId)).thenReturn(Optional.of(entity("invalid-json", now)));

        assertThatThrownBy(() -> service.load(userId))
                .isInstanceOf(IllegalStateException.class).hasMessage("TasteProfile 反序列化失败")
                .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        verifyNoInteractions(legacy, feedback);
    }

    @Test
    void rebuildSkipsDeletedRestaurantsAndUsesZeroDistanceForMissingCandidate() throws Exception {
        UUID missingId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        when(feedback.findByAnonymousUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(
                new UserFeedbackEntity(UUID.randomUUID(), logId, missingId, userId, "LIKE", now),
                new UserFeedbackEntity(UUID.randomUUID(), logId, restaurantId, userId,
                        "LIKE", "[\"SPICY\"]", now)));
        RestaurantEntity restaurant = mock(RestaurantEntity.class);
        when(restaurant.getCategoryCode()).thenReturn("CHINESE");
        when(restaurant.getAveragePrice()).thenReturn(30);
        when(restaurants.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        TasteProfile result = service.load(userId);

        assertThat(result.explicitFeedbackCount()).isEqualTo(1);
        assertThat(result.categoryWeights().get("CHINESE")).isPositive();
        assertThat(result.flavorWeights().get("SPICY")).isPositive();
        verify(candidates).findByRecommendationLogIdAndRestaurantId(logId, restaurantId);
        UserTasteProfileEntity saved = savedProfile();
        assertThat(saved.getAnonymousUserId()).isEqualTo(userId);
        assertThat(saved.getExplicitFeedbackCount()).isEqualTo(1);
        assertThat(mapper.readTree(saved.getDistanceWeightsJson()))
                .isEqualTo(mapper.valueToTree(result.distanceBandWeights()));
    }

    @Test
    void feedbackCreatesProfileAndPersistsTagsAndAlgorithmVersion() throws Exception {
        TasteProfile result = service.updateFeedback(userId, null,
                TestRestaurants.full("a", 4.5, 500), 500, "LIKE", List.of(FlavorTag.SPICY), now);

        UserTasteProfileEntity saved = savedProfile();
        assertThat(result.explicitFeedbackCount()).isEqualTo(1);
        assertThat(saved.getSchemaVersion()).isEqualTo(TasteProfile.SCHEMA_VERSION);
        assertThat(saved.getTasteAlgorithmVersion()).isEqualTo(properties.getAlgorithmVersion());
        assertThat(mapper.readTree(saved.getFlavorWeightsJson()).get("SPICY").asDouble()).isPositive();
        assertThat(saved.getLastFeedbackAt()).isEqualTo(now);
    }

    @Test
    void feedbackReplacesExistingEntityAndKeepsExistingCount() {
        UserTasteProfileEntity existing = entity("{}", now);
        when(profiles.findById(userId)).thenReturn(Optional.of(existing));
        TasteProfile current = new TasteProfile(2, java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), 2, now);

        TasteProfile result = service.update(userId, current,
                TestRestaurants.full("a", 4.5, 500), 500, "DISLIKE", now);

        assertThat(result.explicitFeedbackCount()).isEqualTo(3);
        assertThat(savedProfile()).isSameAs(existing);
        assertThat(existing.getExplicitFeedbackCount()).isEqualTo(3);
        assertThat(result.categoryWeights().get("CHINESE")).isNegative();
    }

    @Test
    void implicitBehaviorPersistsAccumulatorWithoutAddingExplicitFeedback() throws Exception {
        TasteProfile result = service.updateImplicit(userId, TestRestaurants.full("a", 4.5, 500),
                500, List.of(FlavorTag.LIGHT), BehaviorType.ACCEPT, now);

        UserTasteProfileEntity saved = savedProfile();
        assertThat(result.implicitBehaviorCount()).isEqualTo(1);
        assertThat(saved.getExplicitFeedbackCount()).isZero();
        assertThat(mapper.readTree(saved.getImplicitAccumulatorsJson()).get("F:LIGHT").get("count").asInt())
                .isEqualTo(1);
    }

    @Test
    void persistenceFailureIsExposedWithItsCause() {
        RuntimeException failure = new RuntimeException("storage unavailable");
        when(profiles.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.update(userId, TasteProfile.empty(now),
                TestRestaurants.full("a", 4.5, 500), 500, "LIKE", now))
                .isInstanceOf(IllegalStateException.class).hasMessage("TasteProfile 序列化失败")
                .hasCause(failure);
    }

    private UserTasteProfileEntity entity(String categoryJson, LocalDateTime updatedAt) {
        return new UserTasteProfileEntity(userId, TasteProfile.SCHEMA_VERSION,
                properties.getAlgorithmVersion(), categoryJson, "{}", "{}", "{}", "{}",
                2, 0, updatedAt, updatedAt, updatedAt);
    }

    private UserTasteProfileEntity savedProfile() {
        ArgumentCaptor<UserTasteProfileEntity> captor = ArgumentCaptor.forClass(UserTasteProfileEntity.class);
        verify(profiles).save(captor.capture());
        return captor.getValue();
    }
}
