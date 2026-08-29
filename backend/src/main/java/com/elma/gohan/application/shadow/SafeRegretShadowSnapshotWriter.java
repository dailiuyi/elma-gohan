package com.elma.gohan.application.shadow;

import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotEntity;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只在短事务内执行 shadow 快照的查重和落盘。 */
@Service
public class SafeRegretShadowSnapshotWriter {

    private final RecommendationDecisionSnapshotRepository repository;

    public SafeRegretShadowSnapshotWriter(
            RecommendationDecisionSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true,
            timeoutString = "${elma.safe-regret.shadow-execution.transaction-timeout-seconds:2}")
    public Optional<RecommendationDecisionSnapshotEntity> findExisting(
            UUID recommendationLogId, String experimentKey) {
        return repository.findByRecommendationLogIdAndExperimentKey(
                recommendationLogId, experimentKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            timeoutString = "${elma.safe-regret.shadow-execution.transaction-timeout-seconds:2}")
    public RecommendationDecisionSnapshotEntity save(
            RecommendationDecisionSnapshotEntity entity) {
        return repository.saveAndFlush(entity);
    }
}
