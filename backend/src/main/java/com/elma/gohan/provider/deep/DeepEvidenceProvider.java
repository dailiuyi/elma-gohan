package com.elma.gohan.provider.deep;

import com.elma.gohan.domain.restaurant.Restaurant;

/** 按来源查询公开 Web Evidence 的扩展接口。 */
public interface DeepEvidenceProvider {
    DeepEvidenceBatch fetch(DeepEvidenceSource source, Restaurant restaurant);
}
