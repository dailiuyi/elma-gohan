package com.elma.gohan.application;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.*;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.risk.CrossPlatformConsistencyAnalyzer;
import com.elma.gohan.infrastructure.persistence.*;
import com.elma.gohan.provider.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RealtimeEvidenceBudgetTest {
    @Test void coldForegroundOnlyReadsTwoPagesAndDoesNotCacheIncompleteNoMatch() {
        var provider = mock(PlatformEvidenceProvider.class);
        when(provider.searchV3(any(), anyInt(), anyInt())).thenReturn(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, List.of(), 100, 0, 20));
        var repository = mock(ExternalEntityMappingRepository.class);
        when(repository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(any(), any(), any())).thenReturn(List.of());
        var config = new EntityResolutionProperties();
        var aggregator = new EvidenceAggregator(r -> RestaurantEvidence.empty(), provider, new AmapEvidenceAdapter(),
                new EntityResolver(config), new CrossPlatformConsistencyAnalyzer(new RiskProperties()), repository,
                config, new ObjectMapper().findAndRegisterModules(), new BaiduProperties());
        aggregator.collect(List.of(TestRestaurants.full("a", 4.5, 20)), new Location(28.2291, 112.9412), 500, 40);
        verify(provider, times(2)).searchV3(any(), anyInt(), anyInt());
        verify(provider, never()).searchNearby(any(), anyInt(), anyString());
        verify(provider, never()).searchV2(any(), anyInt());
        var saved = ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);
        verify(repository).store(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(saved.getValue().getMatchAlgorithmVersion()).isEqualTo("entity-v0.4");
    }

    @Test void legacyVersionCacheCannotBeReusedByStrictMatcher() {
        var config = new EntityResolutionProperties();
        var repository = mock(ExternalEntityMappingRepository.class);
        var cached = new ExternalEntityMappingEntity(java.util.UUID.randomUUID(), "AMAP", "a", "BAIDU", LocalDateTime.now());
        cached.refresh("b", "NO_MATCH", null, "{}", null, null, null, null, LocalDateTime.now().plusDays(1), LocalDateTime.now());
        cached.setMatchAlgorithmVersion("entity-v0.3-legacy");
        when(repository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(any(), any(), any())).thenReturn(List.of(cached));
        var provider = mock(PlatformEvidenceProvider.class);
        when(provider.searchV3(any(), anyInt(), anyInt())).thenReturn(PlatformSearchResult.unavailable());
        var aggregator = new EvidenceAggregator(r -> RestaurantEvidence.empty(), provider, new AmapEvidenceAdapter(),
                new EntityResolver(config), new CrossPlatformConsistencyAnalyzer(new RiskProperties()), repository,
                config, new ObjectMapper().findAndRegisterModules(), new BaiduProperties());
        aggregator.collect(List.of(TestRestaurants.full("a", 4.5, 20)), new Location(28.2291,112.9412), 500, 40);
        verify(provider).searchV3(any(), anyInt(), eq(0));
    }
}
