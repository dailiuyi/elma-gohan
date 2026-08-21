package com.elma.gohan.domain.recommendation;

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
