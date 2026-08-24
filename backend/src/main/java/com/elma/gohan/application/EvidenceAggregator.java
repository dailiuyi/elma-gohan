package com.elma.gohan.application;

import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.CrossPlatformConsistencyAnalyzer;
import com.elma.gohan.infrastructure.persistence.ExternalEntityMappingEntity;
import com.elma.gohan.infrastructure.persistence.ExternalEntityMappingRepository;
import com.elma.gohan.provider.evidence.AmapEvidenceAdapter;
import com.elma.gohan.provider.evidence.EntityMatchResult;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.EntityResolver;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EvidenceProvider;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.PlatformEvidenceProvider;
import com.elma.gohan.provider.evidence.PlatformSearchResult;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 推荐级 Evidence 编排：最多两次百度调用，自适应选择 V3 第二页或 V2 细评分。
 */
@Service
public class EvidenceAggregator {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAggregator.class);
    private static final String PRIMARY_SOURCE = "AMAP";
    private static final String EVIDENCE_SOURCE = "BAIDU";

    private final EvidenceProvider reviewProvider;
    private final PlatformEvidenceProvider baiduProvider;
    private final AmapEvidenceAdapter amapAdapter;
    private final EntityResolver entityResolver;
    private final CrossPlatformConsistencyAnalyzer consistencyAnalyzer;
    private final ExternalEntityMappingRepository mappingRepository;
    private final EntityResolutionProperties properties;
    private final ObjectMapper objectMapper;

    public EvidenceAggregator(EvidenceProvider reviewProvider,
                              PlatformEvidenceProvider baiduProvider,
                              AmapEvidenceAdapter amapAdapter,
                              EntityResolver entityResolver,
                              CrossPlatformConsistencyAnalyzer consistencyAnalyzer,
                              ExternalEntityMappingRepository mappingRepository,
                              EntityResolutionProperties properties,
                              ObjectMapper objectMapper) {
        this.reviewProvider = reviewProvider;
        this.baiduProvider = baiduProvider;
        this.amapAdapter = amapAdapter;
        this.entityResolver = entityResolver;
        this.consistencyAnalyzer = consistencyAnalyzer;
        this.mappingRepository = mappingRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, EvidenceBundle> collect(List<Restaurant> restaurants, Location center,
                                                int radiusMeters, double poolAveragePrice) {
        Map<String, Double> priceBaselines = new HashMap<>();
        if (poolAveragePrice > 0) {
            for (Restaurant restaurant : restaurants) {
                priceBaselines.put(PriceBaselineCalculator.groupKey(restaurant), poolAveragePrice);
            }
        }
        return collect(restaurants, center, radiusMeters, priceBaselines);
    }

    public Map<String, EvidenceBundle> collect(List<Restaurant> restaurants, Location center,
                                                int radiusMeters,
                                                Map<String, Double> priceBaselines) {
        Instant observedAt = Instant.now();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, PlatformEvidence> amap = new LinkedHashMap<>();
        for (Restaurant restaurant : restaurants) {
            amap.put(restaurant.sourcePoiId(), amapAdapter.from(restaurant, observedAt));
        }

        Map<String, ExternalEntityMappingEntity> stored = loadMappings(restaurants);
        Map<String, EntityMatchResult> matches = new LinkedHashMap<>();
        List<Restaurant> unresolved = new ArrayList<>();
        Set<String> reservedProviderIds = new HashSet<>();
        for (Restaurant restaurant : restaurants) {
            ExternalEntityMappingEntity mapping = stored.get(restaurant.sourcePoiId());
            EntityMatchResult cached = cachedMatch(mapping, now);
            if (cached == null) {
                unresolved.add(restaurant);
            } else {
                matches.put(restaurant.sourcePoiId(), cached);
                if (cached.status() == EntityMatchStatus.MATCHED && cached.evidence() != null) {
                    reservedProviderIds.add(cached.evidence().providerPoiId());
                }
            }
        }

        PlatformSearchResult v3Result = null;
        boolean secondCallUsed = false;
        boolean completeV3Recall = true;
        int baiduCallCount = 0;
        List<Integer> v3PagesRequested = new ArrayList<>();
        if (!unresolved.isEmpty()) {
            baiduCallCount++;
            v3PagesRequested.add(0);
            PlatformSearchResult firstPage = safeSearchV3(center, radiusMeters, 0);
            v3Result = firstPage;
            if (v3Result.status() == EvidenceStatus.UNAVAILABLE) {
                for (Restaurant restaurant : unresolved) {
                    matches.put(restaurant.sourcePoiId(), EntityMatchResult.unavailable());
                }
            } else {
                Map<String, EntityMatchResult> firstPageMatches = entityResolver.resolve(
                        unresolved, firstPage.evidence(), reservedProviderIds);
                if (shouldFetchSecondV3Page(firstPage, firstPageMatches)) {
                    secondCallUsed = true;
                    baiduCallCount++;
                    v3PagesRequested.add(1);
                    PlatformSearchResult secondPage = safeSearchV3(center, radiusMeters, 1);
                    if (secondPage.status() == EvidenceStatus.UNAVAILABLE) {
                        completeV3Recall = false;
                        matches.putAll(firstPageMatches);
                    } else {
                        v3Result = mergeV3Pages(firstPage, secondPage);
                        matches.putAll(entityResolver.resolve(unresolved, v3Result.evidence(),
                                reservedProviderIds));
                    }
                } else {
                    matches.putAll(firstPageMatches);
                }
                for (Restaurant restaurant : unresolved) {
                    String primaryPoiId = restaurant.sourcePoiId();
                    matches.computeIfPresent(primaryPoiId, (ignored, match) ->
                            enrichFromFreshV2Cache(match, stored.get(primaryPoiId), now));
                }
            }
        }

        Set<String> v2RequestedPrimaryIds = new HashSet<>();
        matches.forEach((primaryPoiId, match) -> {
            if (needsV2(match)) v2RequestedPrimaryIds.add(primaryPoiId);
        });
        PlatformSearchResult v2Result = null;
        if (!secondCallUsed && !v2RequestedPrimaryIds.isEmpty()) {
            baiduCallCount++;
            v2Result = safeSearchV2(center, radiusMeters);
        }
        if (v2Result != null && v2Result.status() != EvidenceStatus.UNAVAILABLE) {
            Map<String, PlatformEvidence> byId = new HashMap<>();
            for (PlatformEvidence evidence : v2Result.evidence()) {
                byId.put(evidence.providerPoiId(), evidence);
            }
            matches.replaceAll((primaryPoiId, match) -> enrich(match, byId));
        }
        log.info("百度 Evidence 编排完成 callCount={} v3Pages={} v2Called={} unresolvedCount={}",
                baiduCallCount, v3PagesRequested, v2Result != null, unresolved.size());

        for (Restaurant restaurant : unresolved) {
            EntityMatchResult match = matches.getOrDefault(restaurant.sourcePoiId(),
                    EntityMatchResult.noMatch());
            persistMapping(restaurant, match, v3Result, v2Result,
                    stored.get(restaurant.sourcePoiId()), now, completeV3Recall);
        }
        // V2 可能刷新了原本命中的缓存映射。
        if (v2Result != null && v2Result.status() != EvidenceStatus.UNAVAILABLE) {
            for (Restaurant restaurant : restaurants) {
                if (!unresolved.contains(restaurant)
                        && v2RequestedPrimaryIds.contains(restaurant.sourcePoiId())) {
                    persistMapping(restaurant, matches.get(restaurant.sourcePoiId()), null, v2Result,
                            stored.get(restaurant.sourcePoiId()), now, true);
                }
            }
        }

        Map<String, EvidenceBundle> bundles = new LinkedHashMap<>();
        for (Restaurant restaurant : restaurants) {
            RestaurantEvidence review = safeReviewEvidence(restaurant);
            Double categoryBaseline = priceBaselines == null ? null
                    : priceBaselines.get(PriceBaselineCalculator.groupKey(restaurant));
            if (categoryBaseline != null && categoryBaseline > 0) {
                review = review.withPoolAveragePrice(categoryBaseline);
            }
            EntityMatchResult match = matches.getOrDefault(restaurant.sourcePoiId(),
                    EntityMatchResult.noMatch());
            PlatformEvidence baidu = match.status() == EntityMatchStatus.UNAVAILABLE
                    ? PlatformEvidence.unavailable(EVIDENCE_SOURCE) : match.evidence();
            var consistency = consistencyAnalyzer.analyze(amap.get(restaurant.sourcePoiId()), match);
            bundles.put(restaurant.sourcePoiId(), new EvidenceBundle(review,
                    amap.get(restaurant.sourcePoiId()), baidu, match, consistency));
        }
        return Map.copyOf(bundles);
    }

    private Map<String, ExternalEntityMappingEntity> loadMappings(List<Restaurant> restaurants) {
        List<String> ids = restaurants.stream().map(Restaurant::sourcePoiId).toList();
        Map<String, ExternalEntityMappingEntity> result = new HashMap<>();
        mappingRepository.findByPrimarySourceAndPrimaryPoiIdInAndEvidenceSource(
                PRIMARY_SOURCE, ids, EVIDENCE_SOURCE)
                .forEach(value -> result.put(value.getPrimaryPoiId(), value));
        return result;
    }

    private EntityMatchResult cachedMatch(ExternalEntityMappingEntity mapping, LocalDateTime now) {
        if (mapping == null || mapping.getExpiresAt().isBefore(now)) return null;
        EntityMatchStatus status = EntityMatchStatus.valueOf(mapping.getMatchStatus());
        if (status != EntityMatchStatus.MATCHED) {
            return new EntityMatchResult(status, mapping.getMatchConfidence(), null,
                    fromFeaturesJson(mapping.getMatchFeaturesJson()));
        }
        if (mapping.getEvidenceObservedAt() == null
                || mapping.getEvidenceObservedAt().plusHours(properties.getEvidenceTtlHours())
                .isBefore(now)) return null;
        PlatformEvidence v3 = fromEvidenceJson(mapping.getV3EvidenceJson());
        if (v3 == null) return null;
        boolean v2Fresh = mapping.getV2ObservedAt() != null
                && !mapping.getV2ObservedAt().plusHours(properties.getV2EvidenceTtlHours())
                .isBefore(now);
        if (!hasFineRatings(v3) && !v2Fresh) return null;
        PlatformEvidence merged = v3.mergeOptionalDetails(
                v2Fresh ? fromEvidenceJson(mapping.getV2EvidenceJson()) : null);
        Map<String, Double> features = new LinkedHashMap<>(
                fromFeaturesJson(mapping.getMatchFeaturesJson()));
        if (v2Fresh) features.put("v2Checked", 1.0);
        return new EntityMatchResult(EntityMatchStatus.MATCHED, mapping.getMatchConfidence(),
                merged, features);
    }

    private boolean needsV2(EntityMatchResult match) {
        return match != null && match.status() == EntityMatchStatus.MATCHED
                && match.evidence() != null && !hasFineRatings(match.evidence())
                && match.features().getOrDefault("v2Checked", 0.0) < 1.0;
    }

    private boolean hasFineRatings(PlatformEvidence evidence) {
        return evidence.tasteRating() != null || evidence.serviceRating() != null
                || evidence.environmentRating() != null;
    }

    private boolean shouldFetchSecondV3Page(PlatformSearchResult firstPage,
                                            Map<String, EntityMatchResult> matches) {
        if (firstPage.status() != EvidenceStatus.AVAILABLE
                || firstPage.pageSize() <= 0) {
            return false;
        }
        boolean moreResults = firstPage.total() == null
                ? firstPage.evidence().size() >= firstPage.pageSize()
                : firstPage.total() > firstPage.pageSize();
        boolean hasUnmatched = matches.values().stream()
                .anyMatch(match -> match.status() == EntityMatchStatus.NO_MATCH);
        return moreResults && hasUnmatched;
    }

    private PlatformSearchResult mergeV3Pages(PlatformSearchResult firstPage,
                                               PlatformSearchResult secondPage) {
        Map<String, PlatformEvidence> deduplicated = new LinkedHashMap<>();
        firstPage.evidence().forEach(item -> deduplicated.put(item.providerPoiId(), item));
        secondPage.evidence().forEach(item -> deduplicated.putIfAbsent(item.providerPoiId(), item));
        return new PlatformSearchResult(deduplicated.isEmpty()
                ? EvidenceStatus.NO_DATA : EvidenceStatus.AVAILABLE,
                List.copyOf(deduplicated.values()), firstPage.total(), 0,
                firstPage.pageSize() + secondPage.pageSize());
    }

    private EntityMatchResult enrich(EntityMatchResult match,
                                     Map<String, PlatformEvidence> v2ById) {
        if (match == null || match.status() != EntityMatchStatus.MATCHED
                || match.evidence() == null) return match;
        PlatformEvidence v2 = v2ById.get(match.evidence().providerPoiId());
        Map<String, Double> features = new LinkedHashMap<>(match.features());
        features.put("v2Checked", 1.0);
        return new EntityMatchResult(match.status(), match.confidence(),
                match.evidence().mergeOptionalDetails(v2), features);
    }

    private EntityMatchResult enrichFromFreshV2Cache(EntityMatchResult match,
                                                       ExternalEntityMappingEntity mapping,
                                                       LocalDateTime now) {
        if (match == null || match.status() != EntityMatchStatus.MATCHED
                || match.evidence() == null || mapping == null
                || mapping.getV2ObservedAt() == null
                || mapping.getV2ObservedAt().plusHours(properties.getV2EvidenceTtlHours())
                .isBefore(now)) return match;
        PlatformEvidence cachedV2 = fromEvidenceJson(mapping.getV2EvidenceJson());
        if (cachedV2 == null
                || !match.evidence().providerPoiId().equals(cachedV2.providerPoiId())) return match;
        Map<String, Double> features = new LinkedHashMap<>(match.features());
        features.put("v2Checked", 1.0);
        return new EntityMatchResult(match.status(), match.confidence(),
                match.evidence().mergeOptionalDetails(cachedV2), features);
    }

    private void persistMapping(Restaurant restaurant, EntityMatchResult match,
                                PlatformSearchResult v3Result, PlatformSearchResult v2Result,
                                ExternalEntityMappingEntity existing, LocalDateTime now,
                                boolean cacheNoMatch) {
        ExternalEntityMappingEntity entity = existing == null
                ? new ExternalEntityMappingEntity(UUID.randomUUID(), PRIMARY_SOURCE,
                restaurant.sourcePoiId(), EVIDENCE_SOURCE, now) : existing;
        PlatformEvidence evidence = match.evidence();
        boolean v3Refreshed = v3Result != null;
        String v3Json = v3Refreshed
                ? (evidence == null ? null : toJson(withoutFineRatings(evidence)))
                : existing == null ? null : existing.getV3EvidenceJson();
        LocalDateTime evidenceObservedAt = v3Refreshed
                ? (evidence == null ? null : now)
                : existing == null ? null : existing.getEvidenceObservedAt();
        String v2Json = null;
        LocalDateTime v2ObservedAt = null;
        if (v2Result != null && v2Result.status() != EvidenceStatus.UNAVAILABLE) {
            v2ObservedAt = now;
            if (evidence != null) {
                v2Json = v2Result.evidence().stream()
                        .filter(item -> item.providerPoiId().equals(evidence.providerPoiId()))
                        .findFirst().map(this::toJson).orElse(null);
            }
        } else if (existing != null) {
            v2Json = existing.getV2EvidenceJson();
            v2ObservedAt = existing.getV2ObservedAt();
        }
        LocalDateTime expiresAt = switch (match.status()) {
            case MATCHED -> now.plusDays(properties.getMatchedTtlDays());
            case AMBIGUOUS -> now.plusHours(properties.getAmbiguousTtlHours());
            case NO_MATCH -> cacheNoMatch
                    ? now.plusMinutes(properties.getNoMatchTtlMinutes()) : now;
            // 瞬时平台故障不做负缓存，下次新推荐可以立即恢复。
            case UNAVAILABLE -> now;
        };
        entity.refresh(evidence == null ? null : evidence.providerPoiId(), match.status().name(),
                match.confidence(), toJson(match.features()), v3Json, v2Json,
                evidenceObservedAt, v2ObservedAt, expiresAt, now);
        mappingRepository.save(entity);
    }

    private PlatformEvidence withoutFineRatings(PlatformEvidence evidence) {
        return new PlatformEvidence(evidence.source(), evidence.providerPoiId(), evidence.status(),
                evidence.observedAt(), evidence.name(), evidence.address(), evidence.latitude(),
                evidence.longitude(), evidence.overallRating(), null, null, null,
                evidence.commentCount(), evidence.averagePrice(), evidence.openingHours(),
                evidence.brand(), evidence.telephone());
    }

    private RestaurantEvidence safeReviewEvidence(Restaurant restaurant) {
        try {
            RestaurantEvidence result = reviewProvider.getEvidence(restaurant);
            return result == null ? RestaurantEvidence.unavailable("UNKNOWN") : result;
        } catch (RuntimeException exception) {
            log.warn("评论 Evidence 获取失败，餐厅 {} 已降级", restaurant.sourcePoiId(), exception);
            return RestaurantEvidence.unavailable("PROVIDER");
        }
    }

    private PlatformSearchResult safeSearchV3(Location center, int radiusMeters, int pageNumber) {
        try {
            PlatformSearchResult result = baiduProvider.searchV3(center, radiusMeters, pageNumber);
            return result == null ? PlatformSearchResult.unavailable() : result;
        } catch (RuntimeException exception) {
            log.warn("百度 V3 Evidence 获取失败 page={}，已按高德数据继续推荐 errorType={}",
                    pageNumber, exception.getClass().getSimpleName());
            return PlatformSearchResult.unavailable();
        }
    }

    private PlatformSearchResult safeSearchV2(Location center, int radiusMeters) {
        try {
            PlatformSearchResult result = baiduProvider.searchV2(center, radiusMeters);
            return result == null ? PlatformSearchResult.unavailable() : result;
        } catch (RuntimeException exception) {
            log.warn("百度 V2 Evidence 获取失败，已保留 V3 证据 errorType={}",
                    exception.getClass().getSimpleName());
            return PlatformSearchResult.unavailable();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Evidence JSON 序列化失败", exception);
        }
    }

    private PlatformEvidence fromEvidenceJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, PlatformEvidence.class);
        } catch (JsonProcessingException exception) {
            log.warn("缓存 Evidence 无法读取，将重新请求百度");
            return null;
        }
    }

    private Map<String, Double> fromFeaturesJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
