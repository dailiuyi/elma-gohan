package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.BaiduProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BaiduPlaceRateLimiterTest {

    @Test
    void zeroWaitAllowsImmediatePermitButRejectsQueuedCall() {
        BaiduProperties properties = new BaiduProperties();
        properties.setRateLimitPerSecond(1);
        properties.setRateLimitMaxWaitMs(0);
        BaiduPlaceRateLimiter limiter = new BaiduPlaceRateLimiter(properties);

        assertThat(limiter.acquire().acquired()).isTrue();
        assertThat(limiter.acquire().acquired()).isFalse();
    }

    @Test
    void sharedConcurrentCallsNeverExceedThreeInAnyRollingSecond() throws Exception {
        BaiduProperties properties = new BaiduProperties();
        properties.setRateLimitPerSecond(3);
        properties.setRateLimitMaxWaitMs(4000);
        BaiduPlaceRateLimiter limiter = new BaiduPlaceRateLimiter(properties);
        List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    BaiduPlaceRateLimiter.Permit permit = limiter.acquire();
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
