package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.domain.recommendation.HardFilter;
import com.elma.gohan.domain.recommendation.RecommendationEngine;
import com.elma.gohan.domain.risk.RiskEngine;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.infrastructure.persistence.RiskResultRepository;
import com.elma.gohan.infrastructure.persistence.UserFeedbackRepository;
import com.elma.gohan.provider.poi.PoiProvider;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import com.elma.gohan.provider.poi.PoiSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
                .hasMessageContaining("尚未完成全部检索");
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
        HardFilter hardFilter = mock(HardFilter.class);
        when(hardFilter.filter(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new RecommendationService(provider, mock(EvidenceAggregator.class),
                mock(RiskEngine.class), mock(RecommendationEngine.class), hardFilter,
                mock(TasteProfileService.class), mock(FlavorFeatureService.class),
                mock(UserFoodHistoryService.class), mock(BehaviorService.class),
                mock(RecommendationProperties.class), mock(RiskProperties.class),
                mock(RestaurantRepository.class), mock(RiskResultRepository.class),
                mock(RecommendationLogRepository.class), mock(RecommendationCandidateRepository.class),
                mock(UserFeedbackRepository.class), new ObjectMapper());
    }
}
