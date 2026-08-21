package com.elma.gohan.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantFlavorObservationRepository extends
        JpaRepository<RestaurantFlavorObservationEntity, RestaurantFlavorObservationEntity.Key> {
    List<RestaurantFlavorObservationEntity> findByRestaurantIdIn(Collection<UUID> restaurantIds);
}
