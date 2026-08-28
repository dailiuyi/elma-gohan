package com.elma.gohan.domain.recommendation;

import java.time.LocalDateTime;

/** 某一画像特征尚未结算的隐式行为累加器。 */
public record ImplicitAccumulator(int count, double delta, LocalDateTime updatedAt) {
}
