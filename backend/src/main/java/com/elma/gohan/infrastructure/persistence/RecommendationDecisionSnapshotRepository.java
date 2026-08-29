package com.elma.gohan.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** SafeRegret shadow 决策快照仓库。 */
public interface RecommendationDecisionSnapshotRepository
        extends JpaRepository<RecommendationDecisionSnapshotEntity, UUID> {

    Optional<RecommendationDecisionSnapshotEntity> findByRecommendationLogIdAndExperimentKey(
            UUID recommendationLogId, String experimentKey);

    List<RecommendationDecisionSnapshotEntity> findByRecommendationLogIdOrderByCreatedAtAsc(
            UUID recommendationLogId);

    long deleteByRecommendationLogId(UUID recommendationLogId);
}
