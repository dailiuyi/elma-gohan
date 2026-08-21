package com.elma.gohan.domain.recommendation;

import java.time.LocalDateTime;

public record ImplicitAccumulator(int count, double delta, LocalDateTime updatedAt) {
}
