package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBehaviorRepository extends JpaRepository<UserBehaviorEntity, UUID> {
    Optional<UserBehaviorEntity> findByRecommendationLogIdAndRestaurantIdAndBehaviorType(
            UUID recommendationLogId, UUID restaurantId, String behaviorType);
}
