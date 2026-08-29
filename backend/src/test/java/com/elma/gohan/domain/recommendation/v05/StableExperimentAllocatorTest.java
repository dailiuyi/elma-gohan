package com.elma.gohan.domain.recommendation.v05;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StableExperimentAllocatorTest {

    private final StableExperimentAllocator allocator = new StableExperimentAllocator();

    @Test
    void shadowSwitchPreventsServingRegardlessOfRollout() {
        var allocation = allocator.allocate(UUID.fromString(
                "11111111-1111-1111-1111-111111111111"), "safe-regret-v0.5", false, 100);

        assertThat(allocation.variant()).isEqualTo(StableExperimentAllocator.Variant.SHADOW);
        assertThat(allocation.bucket()).isBetween(0, 9_999);
    }

    @Test
    void sameUserAndExperimentAlwaysReceiveSameBucket() {
        UUID user = UUID.fromString("22222222-2222-2222-2222-222222222222");

        var first = allocator.allocate(user, "safe-regret-v0.5", true, 20);
        var replay = allocator.allocate(user, "safe-regret-v0.5", true, 20);

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void zeroAndFullRolloutHaveExactBoundaries() {
        UUID user = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertThat(allocator.allocate(user, "safe-regret-v0.5", true, 0).variant())
                .isEqualTo(StableExperimentAllocator.Variant.CONTROL);
        assertThat(allocator.allocate(user, "safe-regret-v0.5", true, 100).variant())
                .isEqualTo(StableExperimentAllocator.Variant.CANDIDATE);
    }
}
