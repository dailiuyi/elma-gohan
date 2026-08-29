package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.TestRestaurants;
import com.elma.gohan.application.shadow.SafeRegretShadowDispatcher;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.domain.recommendation.HardFilter;
import com.elma.gohan.domain.recommendation.RecommendationEngine;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskEngine;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RiskResultRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.provider.poi.PoiProvider;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import com.elma.gohan.provider.poi.PoiSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationServiceRecallTest {

    @Test
    @DisplayName("空候选且上游被截断时报告召回未完成")
    void reportsIncompleteSearchWhenEmptyRecallWasTruncated() {
        PoiProvider provider = mock(PoiProvider.class);
        when(provider.nearby(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(emptyRecall(true));
        RecommendationService service = service(provider);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request()))
                .isInstanceOf(PoiSearchIncompleteException.class)
                .hasMessage("附近餐馆已经很多，本次还没完整翻到你选择的距离范围。试试更近一点，或选择更具体的品类。");
    }

    @Test
    @DisplayName("空候选且上游已穷尽时才报告真正没有结果")
    void reportsNoRecommendationWhenEmptyRecallWasExhaustive() {
        PoiProvider provider = mock(PoiProvider.class);
        when(provider.nearby(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(emptyRecall(false));
        RecommendationService service = service(provider);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), request()))
                .isInstanceOf(NoRecommendationAvailableException.class)
                .hasMessageContaining("没有符合条件");
    }

    @Test
    void excludeKeepsOnlyServableCandidateWhenAllAlternativesAreHighRisk() {
        UUID previousId = UUID.randomUUID();
        Restaurant onlyServable = TestRestaurants.full("only-safe", 4.5, 300);
        Restaurant blockedAlternative = TestRestaurants.full("blocked", 4.0, 400);
        RestaurantRepository restaurantRepository = mock(RestaurantRepository.class);
        RestaurantEntity previous = mock(RestaurantEntity.class);
        when(previous.getSource()).thenReturn(onlyServable.source());
        when(previous.getSourcePoiId()).thenReturn(onlyServable.sourcePoiId());
        when(restaurantRepository.findById(previousId)).thenReturn(Optional.of(previous));
        RecommendationService service = service(mock(PoiProvider.class), restaurantRepository);
        Map<String, RiskResult> risks = Map.of(
                onlyServable.sourcePoiId(), risk(RiskLevel.LOW),
                blockedAlternative.sourcePoiId(), risk(RiskLevel.HIGH));

        List<Restaurant> result = service.excludeRequestedRestaurant(
                List.of(onlyServable, blockedAlternative), previousId.toString(), risks);

        assertThat(result).containsExactly(onlyServable, blockedAlternative);
    }

    @Test
    void excludeAppliesWhenAnotherServableCandidateExists() {
        UUID previousId = UUID.randomUUID();
        Restaurant previousRestaurant = TestRestaurants.full("previous", 4.5, 300);
        Restaurant alternative = TestRestaurants.full("alternative", 4.4, 400);
        RestaurantRepository restaurantRepository = mock(RestaurantRepository.class);
        RestaurantEntity previous = mock(RestaurantEntity.class);
        when(previous.getSource()).thenReturn(previousRestaurant.source());
        when(previous.getSourcePoiId()).thenReturn(previousRestaurant.sourcePoiId());
        when(restaurantRepository.findById(previousId)).thenReturn(Optional.of(previous));
        RecommendationService service = service(mock(PoiProvider.class), restaurantRepository);
        Map<String, RiskResult> risks = Map.of(
                previousRestaurant.sourcePoiId(), risk(RiskLevel.LOW),
                alternative.sourcePoiId(), risk(RiskLevel.MEDIUM));

        List<Restaurant> result = service.excludeRequestedRestaurant(
                List.of(previousRestaurant, alternative), previousId.toString(), risks);

        assertThat(result).containsExactly(alternative);
    }

    private static PoiSearchResult emptyRecall(boolean truncated) {
        return new PoiSearchResult(List.of(), new PoiSearchDiagnostics(
                truncated ? 201 : 0, truncated ? 200 : 0, truncated ? 8 : 1,
                0, 0, truncated, null, "050000", null));
    }

    private static CreateRecommendationRequest request() {
        return new CreateRecommendationRequest(28.2, 112.9, 3000,
                2000, 40, 20, "ANY", List.of());
    }

    private static RecommendationService service(PoiProvider provider) {
        return service(provider, mock(RestaurantRepository.class));
    }

    private static RecommendationService service(PoiProvider provider,
                                                 RestaurantRepository restaurantRepository) {
        HardFilter hardFilter = mock(HardFilter.class);
        when(hardFilter.filter(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new RecommendationService(provider, mock(EvidenceAggregator.class),
                mock(RiskEngine.class), mock(RecommendationEngine.class), hardFilter,
                mock(TasteProfileService.class), mock(FlavorFeatureService.class),
                mock(UserFoodHistoryService.class), mock(BehaviorService.class),
                mock(SafeRegretShadowDispatcher.class),
                mock(RecommendationProperties.class), mock(RiskProperties.class),
                restaurantRepository, mock(RiskResultRepository.class),
                mock(RecommendationLogRepository.class), mock(RecommendationCandidateRepository.class),
                mock(UserFeedbackRepository.class), new ObjectMapper());
    }

    private static RiskResult risk(RiskLevel level) {
        return new RiskResult(level == RiskLevel.HIGH ? 80 : 20, level,
                List.of("test"), "risk-v0.3.1");
    }
}
