package com.elma.gohan.application.shadow;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.SafeRegretShadowExecutionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在旧推荐事务提交后写 shadow；失败不会回滚或改变已服务的响应。 */
@Service
public class SafeRegretShadowDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SafeRegretShadowDispatcher.class);
    private static final int MAX_ATTEMPTS = 3;
    private final SafeRegretShadowService shadowService;
    private final SafeRegretProperties properties;
    private final Executor executor;
    private final AsyncTaskExecutor captureExecutor;
    private final long captureTimeoutNanos;
    private final Counter queued;
    private final Counter completed;
    private final Counter retried;
    private final Counter failed;
    private final Counter dropped;

    public SafeRegretShadowDispatcher(
            SafeRegretShadowService shadowService,
            SafeRegretProperties properties,
            @Qualifier("safeRegretShadowExecutor") Executor executor,
            @Qualifier("safeRegretShadowCaptureExecutor")
            AsyncTaskExecutor captureExecutor,
            SafeRegretShadowExecutionProperties executionProperties,
            MeterRegistry meterRegistry) {
        this.shadowService = shadowService;
        this.properties = properties;
        this.executor = executor;
        this.captureExecutor = captureExecutor;
        this.captureTimeoutNanos = TimeUnit.SECONDS.toNanos(
                executionProperties.getCaptureTimeoutSeconds());
        this.queued = counter(meterRegistry, "queued");
        this.completed = counter(meterRegistry, "completed");
        this.retried = counter(meterRegistry, "retried");
        this.failed = counter(meterRegistry, "failed");
        this.dropped = counter(meterRegistry, "dropped");
    }

    public void afterCommit(SafeRegretShadowInput input) {
        if (!properties.isShadowEnabled()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue(input);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueue(input);
                    }
                });
    }

    private void enqueue(SafeRegretShadowInput input) {
        try {
            executor.execute(() -> captureWithRetry(input));
            queued.increment();
        } catch (RuntimeException exception) {
            dropped.increment();
            log.error("SafeRegret shadow 队列拒绝 recommendationId={}",
                    input.recommendationLogId(), exception);
        }
    }

    private void captureWithRetry(SafeRegretShadowInput input) {
        RuntimeException last = null;
        long deadline = System.nanoTime() + captureTimeoutNanos;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                captureOnce(input, remainingNanos(deadline));
                completed.increment();
                return;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failed.increment();
                log.warn("SafeRegret shadow 调度被中断 recommendationId={}",
                        input.recommendationLogId());
                return;
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < MAX_ATTEMPTS && remainingNanos(deadline) > 0L) {
                    retried.increment();
                    if (!backoff(attempt, deadline)) {
                        Thread.currentThread().interrupt();
                        failed.increment();
                        return;
                    }
                } else {
                    break;
                }
            }
        }
        failed.increment();
        log.error("SafeRegret shadow 快照重试耗尽 recommendationId={}",
                input.recommendationLogId(), last);
    }

    private void captureOnce(SafeRegretShadowInput input, long timeoutNanos)
            throws InterruptedException {
        if (timeoutNanos <= 0L) {
            throw new ShadowCaptureTimeoutException();
        }
        Future<?> capture;
        try {
            capture = captureExecutor.submit(() -> shadowService.capture(input));
        } catch (RuntimeException exception) {
            throw exception;
        }
        try {
            capture.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            capture.cancel(true);
            throw new ShadowCaptureTimeoutException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("SafeRegret shadow capture 失败", cause);
        } catch (InterruptedException exception) {
            capture.cancel(true);
            throw exception;
        }
    }

    private boolean backoff(int attempt, long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0L) return true;
        long delay = Math.min(TimeUnit.MILLISECONDS.toNanos(attempt * 100L), remaining);
        try {
            TimeUnit.NANOSECONDS.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            return false;
        }
    }

    private long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("elma.safe_regret.shadow.dispatch")
                .description("SafeRegret shadow dispatch outcomes")
                .tag("outcome", outcome)
                .register(registry);
    }

    private static final class ShadowCaptureTimeoutException extends RuntimeException {
        private ShadowCaptureTimeoutException() {
            super("SafeRegret shadow capture 超时");
        }
    }
}
