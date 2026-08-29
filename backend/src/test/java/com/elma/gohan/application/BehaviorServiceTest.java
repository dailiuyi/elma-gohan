package com.elma.gohan.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elma.gohan.domain.recommendation.BehaviorType;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.UserBehaviorEntity;
import com.elma.gohan.infrastructure.persistence.UserBehaviorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BehaviorServiceTest {

    @Test
    void isolatedRerollIsRecordedButDoesNotMutateLongTermTaste() {
        RecommendationLogRepository logRepository = mock(RecommendationLogRepository.class);
        RecommendationCandidateRepository candidateRepository =
                mock(RecommendationCandidateRepository.class);
        RestaurantRepository restaurantRepository = mock(RestaurantRepository.class);
        UserBehaviorRepository behaviorRepository = mock(UserBehaviorRepository.class);
        FlavorFeatureService flavorFeatureService = mock(FlavorFeatureService.class);
        TasteProfileService tasteProfileService = mock(TasteProfileService.class);
        BehaviorService service = new BehaviorService(logRepository, candidateRepository,
                restaurantRepository, behaviorRepository, flavorFeatureService,
                tasteProfileService, new ObjectMapper());

        UUID userId = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        RecommendationLogEntity log = mock(RecommendationLogEntity.class);
        RecommendationCandidateEntity candidate = mock(RecommendationCandidateEntity.class);
        RestaurantEntity restaurant = mock(RestaurantEntity.class);
        when(log.getId()).thenReturn(logId);
        when(log.getRiskAlgorithmVersion()).thenReturn("risk-v0.3.1");
        when(log.getRecommendationAlgorithmVersion()).thenReturn("recommendation-v0.4.1");
        when(log.getTasteAlgorithmVersion()).thenReturn("taste-v0.1");
        when(candidate.getRestaurantId()).thenReturn(restaurantId);
        when(candidate.getDistanceMeters()).thenReturn(500);
        when(restaurant.getId()).thenReturn(restaurantId);
        when(restaurant.getCategoryCode()).thenReturn("CHINESE");
        when(restaurant.getAveragePrice()).thenReturn(30);
        when(behaviorRepository.findByRecommendationLogIdAndRestaurantIdAndBehaviorType(
                logId, restaurantId, BehaviorType.REROLL.name())).thenReturn(Optional.empty());
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(behaviorRepository.save(any(UserBehaviorEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordReroll(userId, log, candidate, LocalDateTime.now());

        verify(behaviorRepository).save(any(UserBehaviorEntity.class));
        verify(tasteProfileService, never()).updateImplicit(any(), any(),
                any(Integer.class), any(), any(), any());
        verify(flavorFeatureService, never()).loadForRestaurant(any(), any());
    }
}
