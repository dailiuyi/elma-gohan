package com.elma.gohan.provider.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BaiduRequestDispatcherTest {
    @Test
    void fiftyWaitersShareOneCallAndOneTimeoutDoesNotCancelOthers() throws Exception {
        var meters = new SimpleMeterRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(50);
        try (var dispatcher = new BaiduRequestDispatcher(64, meters)) {
            var first = executor.submit(() -> dispatcher.call("same", () -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return empty();
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            List<Future<PlatformSearchResult>> others = new ArrayList<>();
            for (int i = 0; i < 49; i++) {
                others.add(executor.submit(() -> dispatcher.call("same", () -> {
                    calls.incrementAndGet();
                    return empty();
                })));
            }
            waitForCounter(meters, "coalesced", 49);
            try (var ignored = new BaiduCallContext(30)) {
                assertThat(dispatcher.call("same", BaiduRequestDispatcherTest::empty).failureReason())
                        .isEqualTo("LOCAL_QUEUE_TIMEOUT");
            }
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            for (var other : others) assertThat(other.get(2, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queueIsBoundedAndExpiredWorkNeverRuns() throws Exception {
        var meters = new SimpleMeterRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(3);
        try (var dispatcher = new BaiduRequestDispatcher(1, meters)) {
            var first = executor.submit(() -> dispatcher.call("running", () -> {
                entered.countDown();
                await(release);
                return empty();
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var expired = executor.submit(() -> {
                try (var ignored = new BaiduCallContext(150)) {
                    return dispatcher.call("queued", () -> { calls.incrementAndGet(); return empty(); });
                }
            });
            waitForCounter(meters, "queued", 2);
            assertThat(dispatcher.call("overflow", BaiduRequestDispatcherTest::empty).failureReason())
                    .isEqualTo("LOCAL_QUEUE_FULL");
            assertThat(expired.get(1, TimeUnit.SECONDS).failureReason()).isEqualTo("LOCAL_QUEUE_TIMEOUT");
            var replacement = executor.submit(() -> dispatcher.call("replacement", BaiduRequestDispatcherTest::empty));
            waitForCounter(meters, "queued", 3);
            release.countDown();
            assertThat(replacement.get(1, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            assertThat(first.get(1, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            // A subsequent job proves the expired job ahead of it was consumed without executing.
            assertThat(dispatcher.call("after", BaiduRequestDispatcherTest::empty).status()).isEqualTo(EvidenceStatus.NO_DATA);
            assertThat(calls).hasValue(0);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void foregroundHasPriorityWithoutStarvingBackground() throws Exception {
        var meters = new SimpleMeterRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var executor = Executors.newFixedThreadPool(6);
        try (var dispatcher = new BaiduRequestDispatcher(8, meters)) {
            var first = executor.submit(() -> dispatcher.call("running", () -> {
                entered.countDown(); await(release); return empty();
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var background = executor.submit(() -> {
                try (var ignored = new BaiduCallContext(2000, true)) {
                    return dispatcher.call("background", () -> { order.add("background"); return empty(); });
                }
            });
            waitForCounter(meters, "queued", 2);
            List<Future<PlatformSearchResult>> foreground = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String key = "foreground-" + i;
                foreground.add(executor.submit(() -> dispatcher.call(key, () -> { order.add(key); return empty(); })));
            }
            waitForCounter(meters, "queued", 6);
            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            background.get(1, TimeUnit.SECONDS);
            for (var future : foreground) future.get(1, TimeUnit.SECONDS);
            assertThat(order.indexOf("background")).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fatalWorkerFailureReleasesWaitersAndMakesHealthFail() throws Exception {
        var meters = new SimpleMeterRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var dispatcher = new BaiduRequestDispatcher(8, meters)) {
            var failed = executor.submit(() -> dispatcher.call("fatal", () -> {
                entered.countDown(); await(release); throw new AssertionError("simulated fatal failure");
            }));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var waiting = executor.submit(() -> dispatcher.call("waiting", BaiduRequestDispatcherTest::empty));
            waitForCounter(meters, "queued", 2);
            release.countDown();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> failed.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(waiting.get(1, TimeUnit.SECONDS).failureReason()).isEqualTo("DISPATCHER_FAILED");
            assertThat(dispatcher.healthy()).isFalse();
            assertThat(meters.counter("elma.baidu.worker.failures").count()).isEqualTo(1);
            assertThat(dispatcher.call("later", BaiduRequestDispatcherTest::empty).failureReason()).isEqualTo("DISPATCHER_FAILED");
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static PlatformSearchResult empty() {
        return new PlatformSearchResult(EvidenceStatus.NO_DATA, List.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitForCounter(SimpleMeterRegistry meters, String outcome, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        var counter = meters.counter("elma.baidu.dispatch", "outcome", outcome);
        while (counter.count() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(counter.count()).isEqualTo(count);
    }
}
