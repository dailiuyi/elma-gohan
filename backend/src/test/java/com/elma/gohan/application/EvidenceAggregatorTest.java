package com.elma.gohan.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.CrossPlatformConsistencyAnalyzer;
import com.elma.gohan.infrastructure.persistence.ExternalEntityMappingEntity;
import com.elma.gohan.infrastructure.persistence.ExternalEntityMappingRepository;
import com.elma.gohan.provider.evidence.AmapEvidenceAdapter;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.EntityResolver;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.PlatformEvidenceProvider;
import com.elma.gohan.provider.evidence.PlatformSearchResult;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class EvidenceAggregatorTest {

    private final EntityResolutionProperties entityProperties = new EntityResolutionProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void oneRecommendationUsesAtMostV3AndV2AndMergesFineRatings() {
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE,
                        List.of(baidu("b1", null))),
                new PlatformSearchResult(EvidenceStatus.AVAILABLE,
                        List.of(baidu("b1", 4.0))));
        ExternalEntityMappingRepository repository = emptyRepository();
        EvidenceAggregator aggregator = aggregator(provider, repository);
        Restaurant restaurant = TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58);

        Map<String, EvidenceBundle> result = aggregator.collect(List.of(restaurant),
                new Location(28.2291, 112.9412), 1200, 58);

        assertThat(provider.v3Calls).isEqualTo(1);
        assertThat(provider.v2Calls).isEqualTo(1);
        assertThat(result.get("a1").entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.get("a1").baidu().tasteRating()).isEqualTo(4.0);
        verify(repository).save(any(ExternalEntityMappingEntity.class));
    }

    @Test
    void unavailableBaiduKeepsAmapCandidateAndSkipsV2() {
        CountingProvider provider = new CountingProvider(PlatformSearchResult.unavailable(),
                PlatformSearchResult.unavailable());
        EvidenceAggregator aggregator = aggregator(provider, emptyRepository());
        Restaurant restaurant = TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58);

        EvidenceBundle result = aggregator.collect(List.of(restaurant),
                new Location(28.2291, 112.9412), 1200, 58).get("a1");

        assertThat(provider.v3Calls).isEqualTo(1);
        assertThat(provider.v2Calls).isZero();
        assertThat(result.amap().overallRating()).isEqualTo(4.9);
        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.UNAVAILABLE);
        assertThat(result.baidu().status()).isEqualTo(EvidenceStatus.UNAVAILABLE);
    }

    @Test
    void providerExceptionAlsoDegradesWithoutBreakingRecommendation() {
        PlatformEvidenceProvider throwing = new PlatformEvidenceProvider() {
            @Override
            public PlatformSearchResult searchV3(Location center, int radiusMeters,
                                                  int pageNumber) {
                throw new IllegalStateException("simulated provider failure");
            }

            @Override
            public PlatformSearchResult searchV2(Location center, int radiusMeters) {
                throw new AssertionError("V2 must not run after unavailable V3");
            }
        };

        EvidenceBundle result = aggregator(throwing, emptyRepository()).collect(
                List.of(TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58)),
                new Location(28.2291, 112.9412), 1200, 58).get("a1");

        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.UNAVAILABLE);
        assertThat(result.amap().overallRating()).isEqualTo(4.9);
    }

    @Test
    void completeFreshCacheAvoidsRemoteCalls() throws Exception {
        PlatformEvidence cachedEvidence = baidu("b1", 4.0);
        ExternalEntityMappingEntity cached = new ExternalEntityMappingEntity(UUID.randomUUID(),
                "AMAP", "a1", "BAIDU", LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        cached.refresh("b1", "MATCHED", 0.95, "{\"name\":1.0}",
                objectMapper.writeValueAsString(cachedEvidence), null,
                now.minusHours(1), null, now.plusDays(20), now.minusHours(1));
        ExternalEntityMappingRepository repository = mock(ExternalEntityMappingRepository.class);
        when(repository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
                any(), any(), any())).thenReturn(List.of(cached));
        CountingProvider provider = new CountingProvider(PlatformSearchResult.unavailable(),
                PlatformSearchResult.unavailable());

        EvidenceBundle result = aggregator(provider, repository).collect(
                List.of(TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58)),
                new Location(28.2291, 112.9412), 1200, 58).get("a1");

        assertThat(provider.v3Calls).isZero();
        assertThat(provider.v2Calls).isZero();
        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.baidu().tasteRating()).isEqualTo(4.0);
    }

    @Test
    void refreshedV3ReusesStillFreshV2Details() throws Exception {
        PlatformEvidence cachedV3 = baidu("b1", null);
        PlatformEvidence cachedV2 = baidu("b1", 4.0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ExternalEntityMappingEntity cached = new ExternalEntityMappingEntity(UUID.randomUUID(),
                "AMAP", "a1", "BAIDU", now.minusDays(1));
        cached.refresh("b1", "MATCHED", 0.95, "{\"name\":1.0}",
                objectMapper.writeValueAsString(cachedV3),
                objectMapper.writeValueAsString(cachedV2), now.minusHours(7),
                now.minusHours(10), now.plusDays(20), now.minusHours(7));
        ExternalEntityMappingRepository repository = mock(ExternalEntityMappingRepository.class);
        when(repository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
                any(), any(), any())).thenReturn(List.of(cached));
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, List.of(baidu("b1", null))),
                PlatformSearchResult.unavailable());

        EvidenceBundle result = aggregator(provider, repository).collect(
                List.of(TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58)),
                new Location(28.2291, 112.9412), 1200, 58).get("a1");

        assertThat(provider.v3Calls).isEqualTo(1);
        assertThat(provider.v2Calls).isZero();
        assertThat(result.baidu().tasteRating()).isEqualTo(4.0);
        verify(repository).save(any(ExternalEntityMappingEntity.class));
    }

    @Test
    void fullFirstPageWithUnmatchedCandidateUsesSecondV3PageInsteadOfV2() {
        Restaurant restaurant = TestRestaurants.full("a1", "第二页餐厅", 4.7, 80, 48);
        // 百度原始页可能满 20 条，但标准化时会丢弃缺 UID/名称的数据；total 仍应触发翻页。
        List<PlatformEvidence> firstPage = unrelatedPage("p0", 19);
        PlatformEvidence secondPageMatch = namedBaidu("b-target", "第二页餐厅",
                28.2291, 112.9412, null);
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, firstPage, 40, 0, 20),
                new PlatformSearchResult(EvidenceStatus.AVAILABLE,
                        List.of(firstPage.get(0), secondPageMatch), 40, 1, 20),
                PlatformSearchResult.unavailable());

        EvidenceBundle result = aggregator(provider, emptyRepository()).collect(
                List.of(restaurant), new Location(28.2291, 112.9412), 1200, 48).get("a1");

        assertThat(provider.v3Calls).isEqualTo(2);
        assertThat(provider.v3Pages).containsExactly(0, 1);
        assertThat(provider.nearbyCalls).isZero();
        assertThat(provider.suggestionCalls).isZero();
        assertThat(provider.v3Calls + provider.v2Calls).isEqualTo(2);
        assertThat(provider.v2Calls).isZero();
        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.entityMatch().evidence().providerPoiId()).isEqualTo("b-target");
    }

    @Test
    void fullFirstPageWithAllCandidatesMatchedUsesV2InsteadOfSecondPage() {
        List<PlatformEvidence> page = new ArrayList<>(unrelatedPage("p0", 19));
        page.add(baidu("b1", null));
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, page, 40, 0, 20),
                PlatformSearchResult.unavailable(),
                new PlatformSearchResult(EvidenceStatus.AVAILABLE,
                        List.of(baidu("b1", 4.0)), 1, 0, 20));

        EvidenceBundle result = aggregator(provider, emptyRepository()).collect(
                List.of(TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58)),
                new Location(28.2291, 112.9412), 1200, 58).get("a1");

        assertThat(provider.v3Pages).containsExactly(0);
        assertThat(provider.v2Calls).isEqualTo(1);
        assertThat(provider.v3Calls + provider.v2Calls).isEqualTo(2);
        assertThat(result.baidu().tasteRating()).isEqualTo(4.0);
    }

    @Test
    void fullNameThenBrandThenSuggestionRecallMatchesInThatOrder() {
        Restaurant restaurant = TestRestaurants.full("a1", "开坛湘·坛子菜·钵子饭(麓云店)", 4.7, 80, 48);
        List<PlatformEvidence> firstPage = unrelatedPage("p0", 19);
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, firstPage, 19, 0, 20),
                PlatformSearchResult.unavailable(),
                PlatformSearchResult.unavailable());
        provider.suggestion = new PlatformSearchResult(EvidenceStatus.AVAILABLE, List.of(
                namedBaidu("b-suggest", "菜道味·开坛湘·坛子菜·钵子饭(麓云店)",
                        28.2291, 112.9412, null)));

        EvidenceBundle result = aggregator(provider, emptyRepository()).collect(
                List.of(restaurant), new Location(28.2291, 112.9412), 1200, 48).get("a1");

        assertThat(provider.suggestionCalls).isEqualTo(1);
        assertThat(provider.v3Calls).isEqualTo(1);
        assertThat(provider.nearbyQueries).containsExactly(
                "开坛湘坛子菜钵子饭麓云", "开坛湘");
        assertThat(provider.callOrder).containsExactly(
                "v3:0", "nearby:开坛湘坛子菜钵子饭麓云", "nearby:开坛湘",
                "suggestion:开坛湘");
        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.entityMatch().evidence().providerPoiId()).isEqualTo("b-suggest");
    }

    @Test
    void fullNameMissThenBrandNearbyHitUsesTwoNameRequestsWithoutSuggestion() {
        Restaurant restaurant = TestRestaurants.full("a1",
                "开坛湘·坛子菜·钵子饭(麓云店)", 4.7, 80, 48);
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of(), 0, 0, 20),
                PlatformSearchResult.unavailable());
        provider.nearbyByQuery.put("开坛湘",
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, List.of(
                        namedBaidu("b-nearby", "菜道味·开坛湘·坛子菜·钵子饭(麓云店)",
                                28.2291, 112.9412, null))));

        EvidenceBundle result = aggregator(provider, emptyRepository()).collect(
                List.of(restaurant), new Location(28.2291, 112.9412), 1200, 48).get("a1");

        assertThat(provider.nearbyCalls).isEqualTo(2);
        assertThat(provider.nearbyQueries).containsExactly(
                "开坛湘坛子菜钵子饭麓云", "开坛湘");
        assertThat(provider.suggestionCalls).isZero();
        assertThat(result.entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
    }

    @Test
    void nameRecallRequestLimitLeavesSkippedRestaurantWithoutNegativeCache() {
        Restaurant queried = TestRestaurants.full("a1", "第一家湘菜馆", 4.7, 80, 48);
        Restaurant skipped = TestRestaurants.full("a2", "第二家湘菜馆", 4.6, 90, 45);
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of(), 0, 0, 20),
                PlatformSearchResult.unavailable());
        BaiduProperties baiduProperties = new BaiduProperties();
        baiduProperties.setNameRecallMaxRestaurants(10);
        baiduProperties.setNameRecallMaxRequests(3);
        baiduProperties.setRecallMaxCalls(12);
        ExternalEntityMappingRepository repository = emptyRepository();
        ArgumentCaptor<ExternalEntityMappingEntity> saved =
                ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);

        aggregator(provider, repository, baiduProperties).collect(List.of(queried, skipped),
                new Location(28.2291, 112.9412), 1200, 48);

        verify(repository, times(2)).save(saved.capture());
        Map<String, ExternalEntityMappingEntity> mappings = saved.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ExternalEntityMappingEntity::getPrimaryPoiId, value -> value));
        assertThat(mappings.get("a1").getExpiresAt())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(29));
        assertThat(mappings.get("a2").getExpiresAt())
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(5));
    }

    @Test
    void genericPagesAndNameEvidenceAreDeduplicatedByBaiduUid() {
        Restaurant first = TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58);
        Restaurant second = TestRestaurants.full("a2", "第二页餐厅", 4.7, 80, 48);
        List<PlatformEvidence> firstPage = new ArrayList<>(unrelatedPage("p0", 19));
        firstPage.add(baidu("b-shared", null));
        PlatformEvidence duplicateWithFineRating = baidu("b-shared", 4.0);
        PlatformEvidence secondPageMatch = namedBaidu("b-second", "第二页餐厅",
                28.2291, 112.9412, null);
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, firstPage, 40, 0, 20),
                new PlatformSearchResult(EvidenceStatus.AVAILABLE,
                        List.of(duplicateWithFineRating, secondPageMatch), 40, 1, 20),
                PlatformSearchResult.unavailable());

        Map<String, EvidenceBundle> result = aggregator(provider, emptyRepository()).collect(
                List.of(first, second), new Location(28.2291, 112.9412), 1200, 53);

        assertThat(provider.v3Pages).containsExactly(0, 1);
        assertThat(result.get("a1").entityMatch().evidence().providerPoiId())
                .isEqualTo("b-shared");
        assertThat(result.get("a1").baidu().tasteRating()).isNull();
        assertThat(result.get("a2").entityMatch().evidence().providerPoiId())
                .isEqualTo("b-second");
    }

    @Test
    void unavailableSecondPageKeepsFirstPageMatchAndDoesNotNegativeCacheMiss() {
        Restaurant matched = TestRestaurants.full("a1", "湘味小馆", 4.9, 100, 58);
        Restaurant missed = TestRestaurants.full("a2", "第二页餐厅", 4.7, 80, 48);
        List<PlatformEvidence> page = new ArrayList<>(unrelatedPage("p0", 19));
        page.add(baidu("b1", null));
        CountingProvider provider = new CountingProvider(
                new PlatformSearchResult(EvidenceStatus.AVAILABLE, page, 40, 0, 20),
                PlatformSearchResult.unavailable(), PlatformSearchResult.unavailable());
        ExternalEntityMappingRepository repository = emptyRepository();
        ArgumentCaptor<ExternalEntityMappingEntity> saved =
                ArgumentCaptor.forClass(ExternalEntityMappingEntity.class);

        Map<String, EvidenceBundle> result = aggregator(provider, repository).collect(
                List.of(matched, missed), new Location(28.2291, 112.9412), 1200, 53);

        assertThat(provider.v3Calls).isEqualTo(2);
        assertThat(provider.v3Pages).containsExactly(0, 1);
        assertThat(provider.v2Calls).isZero();
        assertThat(result.get("a1").entityMatch().status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(result.get("a2").entityMatch().status()).isEqualTo(EntityMatchStatus.NO_MATCH);
        verify(repository, times(2)).save(saved.capture());
        ExternalEntityMappingEntity missedMapping = saved.getAllValues().stream()
                .filter(value -> "NO_MATCH".equals(value.getMatchStatus()))
                .findFirst().orElseThrow();
        assertThat(missedMapping.getExpiresAt())
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(5));
    }

    private EvidenceAggregator aggregator(PlatformEvidenceProvider provider,
                                           ExternalEntityMappingRepository repository) {
        return aggregator(provider, repository, new BaiduProperties());
    }

    private EvidenceAggregator aggregator(PlatformEvidenceProvider provider,
                                           ExternalEntityMappingRepository repository,
                                           BaiduProperties baiduProperties) {
        return new EvidenceAggregator(restaurant -> RestaurantEvidence.empty(), provider,
                new AmapEvidenceAdapter(), new EntityResolver(entityProperties),
                new CrossPlatformConsistencyAnalyzer(new RiskProperties()), repository,
                entityProperties, objectMapper, baiduProperties);
    }

    private ExternalEntityMappingRepository emptyRepository() {
        ExternalEntityMappingRepository repository = mock(ExternalEntityMappingRepository.class);
        when(repository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
                any(), any(), any())).thenReturn(List.of());
        return repository;
    }

    private static PlatformEvidence baidu(String id, Double tasteRating) {
        return namedBaidu(id, "湘味小馆", 28.2291, 112.9412, tasteRating);
    }

    private static PlatformEvidence namedBaidu(String id, String name, double latitude,
                                                double longitude, Double tasteRating) {
        return new PlatformEvidence("BAIDU", id, EvidenceStatus.AVAILABLE, Instant.now(),
                name, "麓山南路1号", latitude, longitude, 4.2,
                tasteRating, null, null, 2600, 62, "09:00-21:00", null,
                "0731-12345678");
    }

    private static List<PlatformEvidence> unrelatedPage(String prefix, int count) {
        List<PlatformEvidence> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(namedBaidu(prefix + "-" + i, "无关门店" + i,
                    28.2291 + i * 0.00001, 112.9412, null));
        }
        return result;
    }

    private static final class CountingProvider implements PlatformEvidenceProvider {
        private final Map<Integer, PlatformSearchResult> v3ByPage = new HashMap<>();
        private final PlatformSearchResult v2;
        private int v3Calls;
        private int v2Calls;
        private int nearbyCalls;
        private int suggestionCalls;
        private PlatformSearchResult suggestion = new PlatformSearchResult(
                EvidenceStatus.NO_DATA, List.of(), 0, 0, 0);
        private final List<Integer> v3Pages = new ArrayList<>();
        private final List<String> nearbyQueries = new ArrayList<>();
        private final List<String> callOrder = new ArrayList<>();
        private final Map<String, PlatformSearchResult> nearbyByQuery = new HashMap<>();

        private CountingProvider(PlatformSearchResult v3, PlatformSearchResult v2) {
            this(v3, PlatformSearchResult.unavailable(), v2);
        }

        private CountingProvider(PlatformSearchResult firstPage,
                                 PlatformSearchResult secondPage,
                                 PlatformSearchResult v2) {
            this.v3ByPage.put(0, firstPage);
            this.v3ByPage.put(1, secondPage);
            this.v2 = v2;
        }

        @Override
        public PlatformSearchResult searchV3(Location center, int radiusMeters, int pageNumber) {
            v3Calls++;
            v3Pages.add(pageNumber);
            callOrder.add("v3:" + pageNumber);
            return v3ByPage.getOrDefault(pageNumber, PlatformSearchResult.unavailable());
        }

        @Override
        public PlatformSearchResult searchNearby(Location center, int radiusMeters, String query) {
            nearbyCalls++;
            nearbyQueries.add(query);
            callOrder.add("nearby:" + query);
            return nearbyByQuery.getOrDefault(query,
                    new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of(), 0, 0, 20));
        }

        @Override
        public PlatformSearchResult searchV2(Location center, int radiusMeters) {
            v2Calls++;
            callOrder.add("v2");
            return v2;
        }

        @Override
        public PlatformSearchResult searchSuggestion(Location center, String query, String region) {
            suggestionCalls++;
            callOrder.add("suggestion:" + query);
            return suggestion;
        }
    }
}
