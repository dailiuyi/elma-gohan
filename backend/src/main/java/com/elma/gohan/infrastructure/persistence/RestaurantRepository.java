package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantRepository extends JpaRepository<RestaurantEntity, UUID> {

    Optional<RestaurantEntity> findBySourceAndSourcePoiId(String source, String sourcePoiId);
    List<RestaurantEntity> findBySourceAndSourcePoiIdIn(String source, Collection<String> sourcePoiIds);
}
