package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 深挖派生分析缓存仓库。 */
public interface RestaurantDeepAnalysisRepository
        extends JpaRepository<RestaurantDeepAnalysisEntity, UUID> {
    Optional<RestaurantDeepAnalysisEntity> findByRestaurantId(UUID restaurantId);
}
