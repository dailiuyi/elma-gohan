package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 用户行为事件仓库。 */
public interface UserBehaviorRepository extends JpaRepository<UserBehaviorEntity, UUID> {
    Optional<UserBehaviorEntity> findByRecommendationLogIdAndRestaurantIdAndBehaviorType(
            UUID recommendationLogId, UUID restaurantId, String behaviorType);
}
