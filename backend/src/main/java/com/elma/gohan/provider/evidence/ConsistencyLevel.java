package com.elma.gohan.provider.evidence;

/** 多平台数据的一致性等级。 */
public enum ConsistencyLevel {
    CONSISTENT,
    SLIGHT_DIFFERENCE,
    CONFLICT,
    UNKNOWN
}
