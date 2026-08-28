package com.elma.gohan.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 冻结候选池的持久化仓库。 */
public interface RecommendationCandidateRepository
        extends JpaRepository<RecommendationCandidateEntity, UUID> {

    List<RecommendationCandidateEntity> findByRecommendationLogIdOrderBySlotAsc(UUID recommendationLogId);

    Optional<RecommendationCandidateEntity> findByRecommendationLogIdAndRestaurantId(
            UUID recommendationLogId, UUID restaurantId);
}
