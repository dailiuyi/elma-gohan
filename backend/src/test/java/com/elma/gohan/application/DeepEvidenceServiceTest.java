package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.controller.api.DeepEvidenceResponse;
import com.elma.gohan.domain.deep.DeepRiskResult;
import com.elma.gohan.domain.deep.RuleBasedDeepSignalAnalyzer;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepAnalysisRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepEvidenceEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepEvidenceRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.provider.deep.DeepEvidenceBatch;
import com.elma.gohan.provider.deep.DeepEvidenceProvider;
import com.elma.gohan.provider.deep.DeepEvidenceSource;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeepEvidenceServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID recommendationId = UUID.randomUUID();
    private final UUID restaurantId = UUID.randomUUID();
    private final RecommendationLogRepository logs = mock(RecommendationLogRepository.class);
    private final RecommendationCandidateRepository candidates = mock(RecommendationCandidateRepository.class);
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final RestaurantDeepEvidenceRepository evidence = mock(RestaurantDeepEvidenceRepository.class);
    private final RestaurantDeepAnalysisRepository analyses = mock(RestaurantDeepAnalysisRepository.class);
    private final DeepEvidenceProvider provider = mock(DeepEvidenceProvider.class);
    private final DeepEvidenceProperties properties = new DeepEvidenceProperties();

    @BeforeEach
    void setUp() {
        var recommendation = mock(RecommendationLogEntity.class);
        when(recommendation.getCurrentRestaurantId()).thenReturn(restaurantId);
        when(logs.findByIdAndAnonymousUserId(recommendationId, userId)).thenReturn(Optional.of(recommendation));
        var candidate = new RecommendationCandidateEntity(UUID.randomUUID(), recommendationId, restaurantId,
                0, 100, 20, "LOW", 0.8, "{}", "{}", "[]", "base-test", 80, "[]", true);
        when(candidates.findByRecommendationLogIdAndRestaurantId(recommendationId, restaurantId))
                .thenReturn(Optional.of(candidate));
        var now = LocalDateTime.now();
        var restaurant = new RestaurantEntity(restaurantId, "AMAP", "test-poi", "测试餐厅", 28.2,
                112.9, "CHINESE", "中餐", 4.5, 100, 30, BusinessStatus.UNKNOWN,
                "09:00-21:00", "测试路", null, DataCompleteness.FULL, CategoryConfidence.VERIFIED, now, now);
        when(restaurants.findById(restaurantId)).thenReturn(Optional.of(restaurant));
    }

    private DeepEvidenceService service(Executor executor) {
        return new DeepEvidenceService(logs, candidates, restaurants, evidence, analyses, provider,
                new RuleBasedDeepSignalAnalyzer(properties),
                (base, analysis) -> new DeepRiskResult(base.riskScore(), base.riskLevel(), base.confidence(),
                        base.reasons(), "deep-test"), properties,
                new ObjectMapper().findAndRegisterModules(), executor);
    }

    private void assertUnavailable(DeepEvidenceResponse response) {
        assertThat(response.sourceCoverage()).filteredOn(c -> c.resultCount() != null)
                .hasSize(3).allSatisfy(c -> {
                    assertThat(c.status()).isEqualTo("UNAVAILABLE");
                    assertThat(c.resultCount()).isZero();
                });
        assertThat(response.links()).isEmpty();
        assertThat(response.baseRisk().riskScore()).isEqualTo(20);
    }

    @Test
    void providerExceptionDegradesEachSourceAndCachesFailuresWithShortTtl() {
        when(provider.fetch(any(), any(), anyLong())).thenThrow(new IllegalStateException("test provider failure"));
        assertUnavailable(service(Runnable::run).deepen(userId, recommendationId));
        var saved = ArgumentCaptor.forClass(RestaurantDeepEvidenceEntity.class);
        verify(evidence, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(entity -> {
            assertThat(entity.getStatus()).isEqualTo("UNAVAILABLE");
            assertThat(Duration.between(entity.getFetchedAt(), entity.getExpiresAt()))
                    .isEqualTo(Duration.ofMinutes(properties.getFailureCacheMinutes()));
        });
    }

    @Test
    void nullProviderResultIsUnavailableAndDoesNotBreakAnalysis() {
        assertUnavailable(service(Runnable::run).deepen(userId, recommendationId));
        verify(analyses).save(any());
    }

    @Test
    void emptySearchResultsAreNoDataWithEmptyCacheTtl() {
        when(provider.fetch(any(), any(), anyLong())).thenAnswer(invocation ->
                new DeepEvidenceBatch(invocation.getArgument(0), EvidenceStatus.NO_DATA, List.of(), Instant.now()));
        var result = service(Runnable::run).deepen(userId, recommendationId);
        assertThat(result.sourceCoverage()).filteredOn(c -> c.resultCount() != null)
                .hasSize(3).allSatisfy(c -> assertThat(c.status()).isEqualTo("NO_DATA"));
        assertThat(result.links()).isEmpty();
        var saved = ArgumentCaptor.forClass(RestaurantDeepEvidenceEntity.class);
        verify(evidence, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(entity ->
                assertThat(Duration.between(entity.getFetchedAt(), entity.getExpiresAt()))
                        .isEqualTo(Duration.ofMinutes(properties.getEmptyCacheMinutes())));
    }

    @Test
    void evidenceCacheWriteFailureKeepsFetchedSourceStatus() {
        when(provider.fetch(any(), any(), anyLong())).thenAnswer(invocation ->
                new DeepEvidenceBatch(invocation.getArgument(0), EvidenceStatus.NO_DATA, List.of(), Instant.now()));
        when(evidence.save(any())).thenThrow(new IllegalStateException("test database write failed"));
        var response = service(Runnable::run).deepen(userId, recommendationId);
        assertThat(response.sourceCoverage()).filteredOn(c -> c.resultCount() != null)
                .hasSize(3).allSatisfy(c -> assertThat(c.status()).isEqualTo("NO_DATA"));
        verify(analyses).save(any());
    }

    @Test
    void rejectedExecutorDegradesWithoutCallingProvider() {
        assertUnavailable(service(task -> { throw new RejectedExecutionException("test queue full"); })
                .deepen(userId, recommendationId));
        verifyNoInteractions(provider);
    }

    @Test
    void overallTimeoutCancelsQueuedTasksAndReturnsUnavailableSources() {
        properties.setOverallTimeoutMs(10);
        var queued = new java.util.ArrayList<Runnable>();
        assertUnavailable(service(queued::add).deepen(userId, recommendationId));
        assertThat(queued).hasSize(DeepEvidenceSource.values().length);
        // Cancelled futures must not start HTTP work if the executor drains later.
        queued.forEach(Runnable::run);
        verifyNoInteractions(provider);
    }

    @Test
    void failedAnalysisDoesNotLeaveRestaurantStuckInFlight() {
        when(analyses.save(any())).thenThrow(new IllegalStateException("test analysis write failed"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var service = service(Runnable::run);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deepen(userId, recommendationId))
                .hasMessage("test analysis write failed");
        assertUnavailable(service.deepen(userId, recommendationId));
        verify(provider, times(6)).fetch(any(), any(), anyLong());
    }
}
