package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.SafeRegretShadowExecutionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SafeRegretShadowDispatcherTest {

    @Test
    void shadowFailureRetriesOffThreadAndNeverEscapesIntoServedFlow() {
        SafeRegretShadowService shadowService = mock(SafeRegretShadowService.class);
        SafeRegretShadowInput input = mock(SafeRegretShadowInput.class);
        when(input.recommendationLogId()).thenReturn(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        doThrow(new IllegalStateException("shadow unavailable"))
                .when(shadowService).capture(input);
        SafeRegretProperties properties = new SafeRegretProperties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SafeRegretShadowDispatcher dispatcher =
                new SafeRegretShadowDispatcher(shadowService, properties, Runnable::run,
                        syncAsyncExecutor(), executionProperties(), registry);

        assertThatCode(() -> dispatcher.afterCommit(input)).doesNotThrowAnyException();
        verify(shadowService, org.mockito.Mockito.times(3)).capture(input);
        assertThat(counter(registry, "queued")).isEqualTo(1.0);
        assertThat(counter(registry, "retried")).isEqualTo(2.0);
        assertThat(counter(registry, "failed")).isEqualTo(1.0);
    }

    @Test
    void captureIsQueuedInsteadOfRunningOnRequestThread() {
        SafeRegretShadowService shadowService = mock(SafeRegretShadowService.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor executor = queued::set;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SafeRegretShadowDispatcher dispatcher = new SafeRegretShadowDispatcher(
                shadowService, new SafeRegretProperties(), executor,
                syncAsyncExecutor(), executionProperties(), registry);
        SafeRegretShadowInput input = mock(SafeRegretShadowInput.class);

        dispatcher.afterCommit(input);

        verify(shadowService, never()).capture(input);
        queued.get().run();
        verify(shadowService).capture(input);
        assertThat(counter(registry, "queued")).isEqualTo(1.0);
        assertThat(counter(registry, "completed")).isEqualTo(1.0);
    }

    @Test
    void saturatedQueueIsObservedButNeverRunsShadowOnCaller() {
        SafeRegretShadowService shadowService = mock(SafeRegretShadowService.class);
        Executor rejecting = ignored -> { throw new RejectedExecutionException("full"); };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SafeRegretShadowDispatcher dispatcher = new SafeRegretShadowDispatcher(
                shadowService, new SafeRegretProperties(), rejecting,
                syncAsyncExecutor(), executionProperties(), registry);

        assertThatCode(() -> dispatcher.afterCommit(mock(SafeRegretShadowInput.class)))
                .doesNotThrowAnyException();
        verify(shadowService, never()).capture(org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThat(registry.get(
                "elma.safe_regret.shadow.dispatch").tag("outcome", "dropped")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void disabledShadowDoesNotOpenCapturePath() {
        SafeRegretShadowService shadowService = mock(SafeRegretShadowService.class);
        SafeRegretProperties properties = new SafeRegretProperties();
        properties.setShadowEnabled(false);
        SafeRegretShadowDispatcher dispatcher =
                new SafeRegretShadowDispatcher(shadowService, properties, Runnable::run,
                        syncAsyncExecutor(), executionProperties(),
                        new SimpleMeterRegistry());

        dispatcher.afterCommit(mock(SafeRegretShadowInput.class));

        verify(shadowService, never()).capture(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void captureHasOneHardWallClockBudgetAndCancelsWorker() throws Exception {
        SafeRegretShadowService shadowService = mock(SafeRegretShadowService.class);
        SafeRegretShadowInput input = mock(SafeRegretShadowInput.class);
        when(input.recommendationLogId()).thenReturn(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean stopped = new AtomicBoolean();
        doAnswer(ignored -> {
            started.countDown();
            try {
                while (!stopped.get()) TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cancelled", exception);
            }
            return java.util.Optional.empty();
        }).when(shadowService).capture(input);

        SafeRegretShadowExecutionProperties execution = executionProperties();
        execution.setCaptureTimeoutSeconds(1);
        execution.setTransactionTimeoutSeconds(1);
        ThreadPoolTaskExecutor captureExecutor = new ThreadPoolTaskExecutor();
        captureExecutor.setCorePoolSize(1);
        captureExecutor.setMaxPoolSize(1);
        captureExecutor.setQueueCapacity(0);
        captureExecutor.setDaemon(true);
        captureExecutor.initialize();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SafeRegretShadowDispatcher dispatcher = new SafeRegretShadowDispatcher(
                shadowService, new SafeRegretProperties(), Runnable::run,
                captureExecutor, execution, registry);

        long startedAt = System.nanoTime();
        try {
            dispatcher.afterCommit(input);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);

            assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(elapsedMillis).isBetween(900L, 2_500L);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(counter(registry, "failed")).isEqualTo(1.0);
            assertThat(counter(registry, "completed")).isZero();
        } finally {
            stopped.set(true);
            captureExecutor.destroy();
        }
    }

    private SafeRegretShadowExecutionProperties executionProperties() {
        return new SafeRegretShadowExecutionProperties();
    }

    private TaskExecutorAdapter syncAsyncExecutor() {
        return new TaskExecutorAdapter(new SyncTaskExecutor());
    }

    private double counter(SimpleMeterRegistry registry, String outcome) {
        return registry.get("elma.safe_regret.shadow.dispatch")
                .tag("outcome", outcome).counter().count();
    }
}
