package com.elma.gohan.provider.poi.amap;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.AmapProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AmapRateLimiterTest {

    @Test
    void sharedConcurrentCallsNeverExceedThreeInAnyRollingSecond() throws Exception {
        AmapProperties properties = new AmapProperties();
        properties.setRateLimitPerSecond(3);
        AmapRateLimiter limiter = new AmapRateLimiter(properties);
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    AmapRateLimiter.Permit permit = limiter.acquire(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(4));
                    if (permit.acquired()) timestamps.add(System.nanoTime());
                    return null;
                });
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<Long> sorted = timestamps.stream().sorted().toList();
        assertThat(sorted).hasSize(6);
        for (long windowStart : sorted) {
            long calls = sorted.stream()
                    .filter(timestamp -> timestamp >= windowStart
                            && timestamp < windowStart + TimeUnit.SECONDS.toNanos(1))
                    .count();
            assertThat(calls).isLessThanOrEqualTo(3);
        }
    }
}
