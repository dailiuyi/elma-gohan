package com.elma.gohan.provider.evidence;

import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 将主餐厅实体映射为统一高德平台 Evidence。 */
@Component
public class AmapEvidenceAdapter {

    public PlatformEvidence from(Restaurant restaurant, Instant observedAt) {
        return new PlatformEvidence("AMAP", restaurant.sourcePoiId(), EvidenceStatus.AVAILABLE,
                observedAt, restaurant.name(), restaurant.address(), restaurant.latitude(),
                restaurant.longitude(), restaurant.rating(), null, null, null,
                restaurant.reviewCount(), restaurant.averagePrice(), restaurant.openingHours(),
                null, restaurant.telephone());
    }
}
