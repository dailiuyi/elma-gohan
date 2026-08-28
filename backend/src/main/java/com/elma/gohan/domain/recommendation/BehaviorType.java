package com.elma.gohan.domain.recommendation;

/** 推荐流程支持的显式与隐式行为类型。 */
public enum BehaviorType {
    RECOMMENDED,
    ACCEPT,
    REROLL,
    NAVIGATE,
    SKIP,
    LIKE,
    NORMAL,
    DISLIKE;

    public boolean clientWritable() {
        return this == ACCEPT || this == NAVIGATE || this == SKIP;
    }
}
