package com.elma.gohan.domain.restaurant;

/** 高德 POI 餐饮分类的可信程度；明确非餐饮 POI 不进入 Restaurant 模型。 */
public enum CategoryConfidence {
    VERIFIED,
    SUPPORTED,
    INFERRED
}
