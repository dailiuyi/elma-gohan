package com.elma.gohan.application;

import com.elma.gohan.application.shadow.SafeRegretShadowDispatcher;
import com.elma.gohan.application.shadow.SafeRegretShadowInput;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.controller.api.SearchNotice;
import com.elma.gohan.domain.recommendation.RecommendationResult;
import com.elma.gohan.domain.recommendation.RestaurantCandidate;
import com.elma.gohan.domain.recommendation.UserPreference;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.infrastructure.persistence.*;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.poi.PoiSearchDiagnostics;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只保存已完成计算的推荐会话，事务内不访问外部 Provider。 */
@Service
public class RecommendationSessionWriter {
    public record Creation(UUID anonymousUserId, CreateRecommendationRequest request,
            UserPreference preference, List<Restaurant> hardEligible, List<Restaurant> eligible,
            Map<String, EvidenceBundle> evidence, Map<String, RiskResult> risks,
            RecommendationResult result, PoiSearchDiagnostics diagnostics, SearchNotice searchNotice,
            LocalDateTime now) {
        public Creation {
            hardEligible = List.copyOf(hardEligible);
            eligible = List.copyOf(eligible);
            evidence = Map.copyOf(evidence);
            risks = Map.copyOf(risks);
        }
    }
    public record Saved(UUID id, RestaurantCandidate first, int poolSize) { }

    private final RestaurantRepository restaurantRepository;
    private final RiskResultRepository riskResultRepository;
    private final RecommendationLogRepository logRepository;
    private final RecommendationCandidateRepository candidateRepository;
    private final BehaviorService behaviorService;
    private final SafeRegretShadowDispatcher shadowDispatcher;
    private final RecommendationSnapshotJson json;

    public RecommendationSessionWriter(RestaurantRepository restaurantRepository,
            RiskResultRepository riskResultRepository, RecommendationLogRepository logRepository,
            RecommendationCandidateRepository candidateRepository, BehaviorService behaviorService,
            SafeRegretShadowDispatcher shadowDispatcher, RecommendationSnapshotJson json) {
        this.restaurantRepository = restaurantRepository;
        this.riskResultRepository = riskResultRepository;
        this.logRepository = logRepository;
        this.candidateRepository = candidateRepository;
        this.behaviorService = behaviorService;
        this.shadowDispatcher = shadowDispatcher;
        this.json = json;
    }

    @Transactional(timeout = 5)
    public Saved save(Creation creation) {
        UUID anonymousUserId = creation.anonymousUserId();
        var request = creation.request();
        var preference = creation.preference();
        var condition = preference.condition();
        var hardEligible = creation.hardEligible();
        var eligible = creation.eligible();
        var evidence = creation.evidence();
        var risks = creation.risks();
        var result = creation.result();
        var diagnostics = creation.diagnostics();
        var searchNotice = creation.searchNotice();
        var now = creation.now();
        List<RestaurantCandidate> pool = result.pool();
        UUID logId = UUID.randomUUID();

        // 固定行锁顺序，避免两个随机候选池以相反顺序更新相同门店。
        Map<String, Restaurant> savedRestaurants = new java.util.HashMap<>();
        pool.stream().map(RestaurantCandidate::restaurant)
                .sorted(java.util.Comparator.comparing(Restaurant::source).thenComparing(Restaurant::sourcePoiId))
                .forEach(r -> savedRestaurants.put(r.source() + "\u0000" + r.sourcePoiId(), upsertRestaurant(r, now)));

        // upsert restaurant,同时把内部 id 回填到候选
        List<RestaurantCandidate> persisted = new java.util.ArrayList<>(pool.size());
        for (RestaurantCandidate candidate : pool) {
            Restaurant r = candidate.restaurant();
            Restaurant saved = savedRestaurants.get(r.source() + "\u0000" + r.sourcePoiId());
            riskResultRepository.save(new RiskResultEntity(
                    UUID.randomUUID(), saved.id(), candidate.risk().riskScore(),
                    candidate.risk().riskLevel().name(), candidate.risk().confidence(),
                    json.encode(candidate.risk().factors()), json.evidence(candidate.risk()),
                    json.encode(candidate.risk().reasons()),
                    candidate.risk().algorithmVersion(), now));
            persisted.add(new RestaurantCandidate(saved, candidate.risk(),
                    candidate.lowRegretScore(), candidate.reasons(), candidate.personalization()));
        }

        RestaurantCandidate first = persisted.get(0);
        Map<String, Object> conditionSnapshot = new java.util.LinkedHashMap<>();
        conditionSnapshot.put("latitude", request.latitude());
        conditionSnapshot.put("longitude", request.longitude());
        conditionSnapshot.put("minDistance", request.minDistance());
        conditionSnapshot.put("radius", condition.radius());
        conditionSnapshot.put("minBudget", request.minBudget());
        conditionSnapshot.put("maxBudget", request.maxBudget());
        conditionSnapshot.put("category", condition.category());
        conditionSnapshot.put("dislikes", condition.dislikes());
        conditionSnapshot.put("excludeRestaurantId", request.excludeRestaurantId());
        RecommendationLogEntity recommendationLog = logRepository.save(new RecommendationLogEntity(
                logId, anonymousUserId, json.encode(conditionSnapshot),
                persisted.size(), first.restaurant().id(), first.restaurant().id(),
                first.risk().riskScore(), first.lowRegretScore(),
                first.risk().algorithmVersion(), result.algorithmVersion(),
                first.personalization().algorithmVersion(),
                first.personalization().selectionMode().name(), result.randomSeed(),
                json.encode(result.selectionSnapshot()), json.encode(recallSnapshot(diagnostics, searchNotice)),
                now));

        RecommendationCandidateEntity firstEntity = null;
        for (int i = 0; i < persisted.size(); i++) {
            RestaurantCandidate candidate = persisted.get(i);
            RecommendationCandidateEntity candidateEntity = candidateRepository.save(
                    new RecommendationCandidateEntity(
                    UUID.randomUUID(), logId, candidate.restaurant().id(), i + 1,
                    candidate.restaurant().distanceMeters(),
                    candidate.risk().riskScore(), candidate.risk().riskLevel().name(),
                    candidate.risk().confidence(), json.encode(candidate.risk().factors()),
                    json.evidence(candidate.risk()),
                    json.encode(candidate.risk().reasons()), candidate.risk().algorithmVersion(),
                    candidate.lowRegretScore(), json.encode(candidate.reasons()),
                    candidate.personalization().tasteMatchScore(),
                    candidate.personalization().confidence(),
                    json.encode(candidate.personalization().scoreBreakdown()),
                    json.encode(candidate.personalization().reasons()),
                    candidate.personalization().selectionMode().name(), i == 0));
            if (i == 0) firstEntity = candidateEntity;
        }

        behaviorService.recordRecommended(anonymousUserId, recommendationLog,
                java.util.Objects.requireNonNull(firstEntity), now);

        shadowDispatcher.afterCommit(new SafeRegretShadowInput(
                anonymousUserId, logId, hardEligible, eligible, evidence, risks,
                preference, result, result.randomSeed(), now));

        return new Saved(logId, persisted.get(0), persisted.size());
    }

    private Restaurant upsertRestaurant(Restaurant r, LocalDateTime now) {
        restaurantRepository.store(new RestaurantEntity(
                UUID.randomUUID(), r.source(), r.sourcePoiId(),
                r.name(), r.latitude(), r.longitude(), r.categoryCode(), r.categoryLabel(),
                r.rating(), r.reviewCount(), r.averagePrice(), r.businessStatus(),
                r.openingHours(), r.address(), r.telephone(), r.dataCompleteness(),
                r.categoryConfidence(),
                now, now));
        return restaurantRepository.findBySourceAndSourcePoiId(r.source(), r.sourcePoiId())
                .orElseThrow().toRestaurant(r.distanceMeters());
    }

    private Map<String, Object> recallSnapshot(PoiSearchDiagnostics diagnostics,
                                               SearchNotice searchNotice) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("completionReason", diagnostics.completionReason());
        snapshot.put("truncated", diagnostics.incomplete());
        snapshot.put("pagesFetched", diagnostics.pagesFetched());
        snapshot.put("retryCount", diagnostics.retryCount());
        snapshot.put("fetchedCount", diagnostics.fetchedCount());
        snapshot.put("mappedCount", diagnostics.mappedCount());
        snapshot.put("cacheStatus", diagnostics.cacheStatus());
        if (searchNotice != null) snapshot.put("searchNotice", searchNotice);
        return snapshot;
    }

}
