package com.elma.gohan.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 餐厅主实体仓库。 */
public interface RestaurantRepository extends JpaRepository<RestaurantEntity, UUID> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
        INSERT INTO restaurant(id,source,source_poi_id,name,latitude,longitude,category_code,category_label,
          rating,review_count,average_price,business_status,opening_hours,address,telephone,
          data_completeness,category_confidence,created_at,updated_at)
        VALUES (:#{#value.id},:#{#value.source},:#{#value.sourcePoiId},:#{#value.name},
          :#{#value.latitude},:#{#value.longitude},:#{#value.categoryCode},:#{#value.categoryLabel},
          :#{#value.rating},:#{#value.reviewCount},:#{#value.averagePrice},:#{#value.businessStatus.name()},
          :#{#value.openingHours},:#{#value.address},:#{#value.telephone},
          :#{#value.dataCompleteness.name()},:#{#value.categoryConfidence.name()},:#{#value.createdAt},:#{#value.updatedAt})
        ON CONFLICT(source,source_poi_id) DO UPDATE SET
          name=excluded.name,latitude=excluded.latitude,longitude=excluded.longitude,
          category_code=excluded.category_code,category_label=excluded.category_label,
          rating=excluded.rating,review_count=excluded.review_count,average_price=excluded.average_price,
          business_status=excluded.business_status,opening_hours=excluded.opening_hours,
          address=excluded.address,telephone=excluded.telephone,data_completeness=excluded.data_completeness,
          category_confidence=excluded.category_confidence,updated_at=excluded.updated_at
        WHERE restaurant.updated_at <= excluded.updated_at
        """, nativeQuery = true)
    void store(@org.springframework.data.repository.query.Param("value") RestaurantEntity value);

    Optional<RestaurantEntity> findBySourceAndSourcePoiId(String source, String sourcePoiId);
    List<RestaurantEntity> findBySourceAndSourcePoiIdIn(String source, Collection<String> sourcePoiIds);
}
