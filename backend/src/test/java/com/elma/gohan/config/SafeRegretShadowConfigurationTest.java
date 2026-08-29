package com.elma.gohan.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SafeRegretShadowConfigurationTest {

    private final SafeRegretShadowConfiguration configuration =
            new SafeRegretShadowConfiguration();

    @Test
    void executorsAreBoundedAndUseDaemonWorkers() throws Exception {
        SafeRegretShadowExecutionProperties properties =
                new SafeRegretShadowExecutionProperties();
        ThreadPoolTaskExecutor dispatch = (ThreadPoolTaskExecutor)
                configuration.safeRegretShadowExecutor(properties);
        AsyncTaskExecutor captureApi = configuration
                .safeRegretShadowCaptureExecutor(properties);
        ThreadPoolTaskExecutor capture = (ThreadPoolTaskExecutor) captureApi;

        try {
            assertThat(dispatch.getCorePoolSize()).isEqualTo(2);
            assertThat(dispatch.getMaxPoolSize()).isEqualTo(2);
            assertThat(dispatch.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(128);
            assertThat(capture.getCorePoolSize()).isEqualTo(2);
            assertThat(capture.getMaxPoolSize()).isEqualTo(2);
            assertThat(capture.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isZero();

            Future<ThreadView> dispatchThread = dispatch.submit(ThreadView::current);
            Future<ThreadView> captureThread = capture.submit(ThreadView::current);
            ThreadView dispatchView = dispatchThread.get(1, TimeUnit.SECONDS);
            ThreadView captureView = captureThread.get(1, TimeUnit.SECONDS);
            assertThat(dispatchView.daemon()).isTrue();
            assertThat(dispatchView.name()).startsWith("safe-regret-shadow-")
                    .doesNotStartWith("safe-regret-shadow-capture-");
            assertThat(captureView.daemon()).isTrue();
            assertThat(captureView.name())
                    .startsWith("safe-regret-shadow-capture-");
        } finally {
            dispatch.destroy();
            capture.destroy();
        }
    }

    @Test
    void destroyInterruptsImmediatelyAndReturnsAtConfiguredHardLimit()
            throws Exception {
        SafeRegretShadowExecutionProperties properties =
                new SafeRegretShadowExecutionProperties();
        properties.setShutdownTimeoutSeconds(1);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                configuration.safeRegretShadowCaptureExecutor(properties);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                // 模拟不配合首次中断的第三方调用；daemon 保证它不能阻止 JVM 退出。
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        // 测试目标就是验证即使任务不配合，关闭调用仍有硬上限。
                    }
                }
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        executor.destroy();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt);
        try {
            assertThat(interrupted.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(elapsedMillis).isBetween(900L, 2_500L);
        } finally {
            release.countDown();
        }
    }

    private record ThreadView(boolean daemon, String name) {
        static ThreadView current() {
            Thread thread = Thread.currentThread();
            return new ThreadView(thread.isDaemon(), thread.getName());
        }
    }
}
