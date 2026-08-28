package com.elma.gohan.application;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.controller.api.DeepEvidenceResponse;
import com.elma.gohan.controller.api.EvidenceSummaryResponse;
import com.elma.gohan.controller.api.RiskAssessment;
import com.elma.gohan.domain.deep.BaseRiskSnapshot;
import com.elma.gohan.domain.deep.DeepRiskEngine;
import com.elma.gohan.domain.deep.DeepRiskResult;
import com.elma.gohan.domain.deep.DeepSignalAnalysis;
import com.elma.gohan.domain.deep.DeepSignalAnalyzer;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationCandidateRepository;
import com.elma.gohan.infrastructure.persistence.RecommendationLogEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationLogRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepAnalysisEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepAnalysisRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepEvidenceEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantDeepEvidenceRepository;
import com.elma.gohan.infrastructure.persistence.RestaurantEntity;
import com.elma.gohan.infrastructure.persistence.RestaurantRepository;
import com.elma.gohan.provider.deep.DeepEvidenceBatch;
import com.elma.gohan.provider.deep.DeepEvidenceProvider;
import com.elma.gohan.provider.deep.DeepEvidenceSource;
import com.elma.gohan.provider.deep.WebEvidenceItem;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.EvidenceSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 按需聚合公开 Web 线索并生成独立的深挖风险结果。 */
@Service
public class DeepEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(DeepEvidenceService.class);

    private final RecommendationLogRepository logRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantDeepEvidenceRepository evidenceRepository;
    private final RestaurantDeepAnalysisRepository analysisRepository;
    private final DeepEvidenceProvider provider;
    private final DeepSignalAnalyzer signalAnalyzer;
    private final DeepRiskEngine deepRiskEngine;
    private final DeepEvidenceProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final ConcurrentHashMap<UUID, CompletableFuture<CollectedEvidence>> inFlight =
            new ConcurrentHashMap<>();

    public DeepEvidenceService(RecommendationLogRepository logRepository,
                               RecommendationCandidateRepository candidateRepository,
                               RestaurantRepository restaurantRepository,
                               RestaurantDeepEvidenceRepository evidenceRepository,
                               RestaurantDeepAnalysisRepository analysisRepository,
                               DeepEvidenceProvider provider,
                               DeepSignalAnalyzer signalAnalyzer,
                               DeepRiskEngine deepRiskEngine,
                               DeepEvidenceProperties properties,
                               ObjectMapper objectMapper,
                               @Qualifier("deepEvidenceExecutor") Executor executor) {
        this.logRepository = logRepository;
        this.candidateRepository = candidateRepository;
        this.restaurantRepository = restaurantRepository;
        this.evidenceRepository = evidenceRepository;
        this.analysisRepository = analysisRepository;
        this.provider = provider;
        this.signalAnalyzer = signalAnalyzer;
        this.deepRiskEngine = deepRiskEngine;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** 深挖当前推荐餐厅，优先复用缓存且不改变主推荐排序。 */
    public DeepEvidenceResponse deepen(UUID anonymousUserId, UUID recommendationId) {
        long started = System.nanoTime();
        RecommendationLogEntity recommendation = logRepository
                .findByIdAndAnonymousUserId(recommendationId, anonymousUserId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        UUID restaurantId = recommendation.getCurrentRestaurantId();
        RecommendationCandidateEntity candidate = candidateRepository
                .findByRecommendationLogIdAndRestaurantId(recommendationId, restaurantId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        RestaurantEntity restaurantEntity = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new RecommendationNotFoundException("推荐已失效,请重新获取"));
        Restaurant restaurant = toDomain(restaurantEntity, candidate.getDistanceMeters());

        CollectedEvidence collected = collectSingleFlight(restaurant);
        BaseRiskSnapshot base = new BaseRiskSnapshot(candidate.getRiskScore(),
                RiskLevel.valueOf(candidate.getRiskLevel()), candidate.getRiskConfidence(),
                fromStringList(candidate.getRiskReasonsJson()), candidate.getRiskAlgorithmVersion());
        DeepRiskResult deepRisk = deepRiskEngine.evaluate(base, collected.analysis());
        EvidenceSummary structured = fromEvidenceSummary(candidate.getEvidenceSummaryJson());

        List<DeepEvidenceResponse.SourceCoverage> coverage = new ArrayList<>();
        coverage.add(new DeepEvidenceResponse.SourceCoverage("AMAP",
                structured == null || structured.amap() == null
                        ? EvidenceStatus.UNAVAILABLE.name() : structured.amap().status().name(), null));
        coverage.add(new DeepEvidenceResponse.SourceCoverage("BAIDU",
                structured == null || structured.baidu() == null
                        ? EvidenceStatus.UNAVAILABLE.name() : structured.baidu().status().name(), null));
        for (DeepEvidenceSource source : DeepEvidenceSource.values()) {
            SourceMaterial material = collected.materials().get(source);
            coverage.add(new DeepEvidenceResponse.SourceCoverage(source.name(),
                    material.batch().status().name(), material.batch().items().size()));
        }

        List<DeepEvidenceResponse.EvidenceLink> links = new ArrayList<>();
        for (DeepEvidenceSource source : DeepEvidenceSource.values()) {
            collected.materials().get(source).batch().items().stream()
                    .limit(properties.getMaxLinksPerSource())
                    .forEach(item -> links.add(new DeepEvidenceResponse.EvidenceLink(
                            source.name(), item.title(), item.url(),
                            item.publishedAt() == null ? null : item.publishedAt().toString())));
        }
        DeepSignalAnalysis analysis = collected.analysis();
        log.info("深挖完成 userHash={} restaurantId={} cacheStatus={} sourceCalls={} "
                        + "resultCount={} durationMs={} algorithm={}",
                shortHash(anonymousUserId.toString()), restaurantId, collected.cacheStatus(),
                collected.sourceCalls(), analysis.relevantResultCount(), elapsedMillis(started),
                deepRisk.algorithmVersion());
        return new DeepEvidenceResponse(recommendationId.toString(), restaurantId.toString(),
                restaurant.name(), toAssessment(base), toAssessment(deepRisk),
                EvidenceSummaryResponse.from(structured), List.copyOf(coverage),
                new DeepEvidenceResponse.SignalSummary(analysis.positive(), analysis.negative(),
                        analysis.cautions()),
                new DeepEvidenceResponse.Consistency(analysis.consistencyLevel(),
                        analysis.consistencyReason()),
                List.copyOf(links), collected.cacheStatus(),
                analysis.generatedAt().toString(), analysis.expiresAt().toString());
    }

    private CollectedEvidence collectSingleFlight(Restaurant restaurant) {
        CompletableFuture<CollectedEvidence> mine = new CompletableFuture<>();
        CompletableFuture<CollectedEvidence> existing = inFlight.putIfAbsent(restaurant.id(), mine);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException exception) {
                throw unwrap(exception);
            }
        }
        try {
            CollectedEvidence result = collect(restaurant);
            mine.complete(result);
            return result;
        } catch (RuntimeException exception) {
            mine.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(restaurant.id(), mine);
        }
    }

    private CollectedEvidence collect(Restaurant restaurant) {
        Instant now = Instant.now();
        Map<DeepEvidenceSource, SourceMaterial> materials =
                new EnumMap<>(DeepEvidenceSource.class);
        Map<DeepEvidenceSource, CompletableFuture<SourceMaterial>> pending =
                new EnumMap<>(DeepEvidenceSource.class);
        for (DeepEvidenceSource source : DeepEvidenceSource.values()) {
            SourceMaterial cached = loadCached(restaurant, source, now);
            if (cached != null) {
                materials.put(source, cached);
            } else {
                try {
                    pending.put(source, CompletableFuture.supplyAsync(
                            () -> fetchAndCache(restaurant, source), executor));
                } catch (RuntimeException exception) {
                    materials.put(source, unavailableMaterial(source, now));
                }
            }
        }

        if (!pending.isEmpty()) {
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    pending.values().toArray(CompletableFuture[]::new));
            try {
                all.get(properties.getOverallTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                pending.values().forEach(future -> future.cancel(true));
                log.warn("Brave 深挖批次超时 sourceCalls={} errorType={}", pending.size(),
                        exception.getClass().getSimpleName());
            }
            pending.forEach((source, future) -> {
                if (future.isDone() && !future.isCompletedExceptionally()
                        && !future.isCancelled()) {
                    materials.put(source, future.join());
                } else {
                    materials.put(source, unavailableMaterial(source, now));
                }
            });
        }

        String fingerprint = evidenceFingerprint(materials);
        DeepSignalAnalysis analysis = loadAnalysis(restaurant.id(), fingerprint, now);
        if (analysis == null) {
            Map<DeepEvidenceSource, DeepEvidenceBatch> batches = new EnumMap<>(DeepEvidenceSource.class);
            materials.forEach((source, material) -> batches.put(source, material.batch()));
            analysis = signalAnalyzer.analyze(batches, now);
            saveAnalysis(restaurant.id(), fingerprint, analysis, now);
        }
        long hits = materials.values().stream().filter(SourceMaterial::cacheHit).count();
        String cacheStatus = hits == DeepEvidenceSource.values().length ? "HIT"
                : hits == 0 ? "MISS" : "PARTIAL_HIT";
        return new CollectedEvidence(Map.copyOf(materials), analysis, cacheStatus, pending.size());
    }

    private SourceMaterial loadCached(Restaurant restaurant, DeepEvidenceSource source,
                                      Instant now) {
        String queryFingerprint = queryFingerprint(restaurant, source);
        return evidenceRepository.findByRestaurantIdAndSource(restaurant.id(), source.name())
                .filter(entity -> entity.getExpiresAt().isAfter(toLocal(now)))
                .filter(entity -> queryFingerprint.equals(entity.getQueryFingerprint()))
                .map(entity -> new SourceMaterial(new DeepEvidenceBatch(source,
                                EvidenceStatus.valueOf(entity.getStatus()),
                                fromItems(entity.getEvidenceJson()), toInstant(entity.getFetchedAt())),
                        toInstant(entity.getExpiresAt()), true))
                .orElse(null);
    }

    private SourceMaterial fetchAndCache(Restaurant restaurant, DeepEvidenceSource source) {
        Instant fetchedAt = Instant.now();
        DeepEvidenceBatch batch;
        try {
            batch = provider.fetch(source, restaurant);
            if (batch == null) batch = DeepEvidenceBatch.unavailable(source, fetchedAt);
        } catch (RuntimeException exception) {
            log.warn("深挖 Provider 异常 source={} errorType={}", source,
                    exception.getClass().getSimpleName());
            batch = DeepEvidenceBatch.unavailable(source, fetchedAt);
        }
        Instant expiresAt = batch.status() == EvidenceStatus.UNAVAILABLE
                ? fetchedAt.plus(properties.getFailureCacheMinutes(), ChronoUnit.MINUTES)
                : fetchedAt.plus(properties.getEvidenceCacheHours(), ChronoUnit.HOURS);
        try {
            LocalDateTime now = toLocal(fetchedAt);
            RestaurantDeepEvidenceEntity entity = evidenceRepository
                    .findByRestaurantIdAndSource(restaurant.id(), source.name())
                    .orElseGet(() -> new RestaurantDeepEvidenceEntity(
                            UUID.randomUUID(), restaurant.id(), source.name(), now));
            entity.refresh(batch.status().name(), queryFingerprint(restaurant, source),
                    toJson(batch.items()), now, toLocal(expiresAt), now);
            evidenceRepository.save(entity);
        } catch (RuntimeException exception) {
            log.warn("深挖缓存写入失败 source={} errorType={}", source,
                    exception.getClass().getSimpleName());
        }
        return new SourceMaterial(batch, expiresAt, false);
    }

    private SourceMaterial unavailableMaterial(DeepEvidenceSource source, Instant now) {
        return new SourceMaterial(DeepEvidenceBatch.unavailable(source, now),
                now.plus(properties.getFailureCacheMinutes(), ChronoUnit.MINUTES), false);
    }

    private DeepSignalAnalysis loadAnalysis(UUID restaurantId, String fingerprint, Instant now) {
        return analysisRepository.findByRestaurantId(restaurantId)
                .filter(entity -> fingerprint.equals(entity.getEvidenceFingerprint()))
                .filter(entity -> properties.getAnalysisAlgorithmVersion()
                        .equals(entity.getAlgorithmVersion()))
                .filter(entity -> entity.getExpiresAt().isAfter(toLocal(now)))
                .map(entity -> fromJson(entity.getAnalysisJson(), DeepSignalAnalysis.class))
                .orElse(null);
    }

    private void saveAnalysis(UUID restaurantId, String fingerprint,
                              DeepSignalAnalysis analysis, Instant now) {
        LocalDateTime localNow = toLocal(now);
        RestaurantDeepAnalysisEntity entity = analysisRepository.findByRestaurantId(restaurantId)
                .orElseGet(() -> new RestaurantDeepAnalysisEntity(
                        UUID.randomUUID(), restaurantId, localNow));
        entity.refresh(fingerprint, toJson(analysis), analysis.algorithmVersion(),
                toLocal(analysis.generatedAt()), toLocal(analysis.expiresAt()), localNow);
        analysisRepository.save(entity);
    }

    private String queryFingerprint(Restaurant restaurant, DeepEvidenceSource source) {
        return sha256(properties.getQueryVersion() + "|" + source.name() + "|"
                + restaurant.name() + "|" + restaurant.address());
    }

    private String evidenceFingerprint(Map<DeepEvidenceSource, SourceMaterial> materials) {
        Map<String, Object> stable = new LinkedHashMap<>();
        for (DeepEvidenceSource source : DeepEvidenceSource.values()) {
            SourceMaterial material = materials.get(source);
            stable.put(source.name() + ":status", material.batch().status().name());
            stable.put(source.name() + ":items", material.batch().items());
        }
        return sha256(toJson(stable));
    }

    private Restaurant toDomain(RestaurantEntity entity, int distanceMeters) {
        return new Restaurant(entity.getId(), entity.getSource(), entity.getSourcePoiId(),
                entity.getName(), entity.getLatitude(), entity.getLongitude(), distanceMeters,
                entity.getCategoryCode(), entity.getCategoryLabel(), entity.getRating(),
                entity.getReviewCount(), entity.getAveragePrice(), entity.getBusinessStatus(),
                entity.getOpeningHours(), entity.getAddress(), entity.getTelephone(),
                entity.getDataCompleteness() == null
                        ? DataCompleteness.MINIMAL : entity.getDataCompleteness(),
                entity.getCategoryConfidence());
    }

    private RiskAssessment toAssessment(BaseRiskSnapshot risk) {
        return new RiskAssessment(risk.riskScore(), risk.riskLevel().name(), risk.confidence(),
                risk.reasons(), risk.algorithmVersion());
    }

    private RiskAssessment toAssessment(DeepRiskResult risk) {
        return new RiskAssessment(risk.riskScore(), risk.riskLevel().name(), risk.confidence(),
                risk.reasons(), risk.algorithmVersion());
    }

    private EvidenceSummary fromEvidenceSummary(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        return fromJson(json, EvidenceSummary.class);
    }

    private List<String> fromStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("深挖基础风险反序列化失败", exception);
        }
    }

    private List<WebEvidenceItem> fromItems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<WebEvidenceItem>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("深挖 Evidence 反序列化失败", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("深挖缓存反序列化失败", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("深挖缓存序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String shortHash(String value) {
        return sha256(value).substring(0, 12);
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private RuntimeException unwrap(CompletionException exception) {
        return exception.getCause() instanceof RuntimeException runtime ? runtime : exception;
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record SourceMaterial(DeepEvidenceBatch batch, Instant expiresAt, boolean cacheHit) { }
    private record CollectedEvidence(Map<DeepEvidenceSource, SourceMaterial> materials,
                                     DeepSignalAnalysis analysis, String cacheStatus,
                                     int sourceCalls) { }
}
