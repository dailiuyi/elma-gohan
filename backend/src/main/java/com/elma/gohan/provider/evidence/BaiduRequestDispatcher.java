package com.elma.gohan.provider.evidence;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 同查询合并；三个前台任务后给后台一次机会。等待者只等待 Future。 */
final class BaiduRequestDispatcher implements AutoCloseable {
    private static final class Job {
        final String key;
        final boolean background;
        final Supplier<PlatformSearchResult> action;
        final AtomicLong deadline;
        final CompletableFuture<PlatformSearchResult> result = new CompletableFuture<>();
        final long enqueuedAt = System.nanoTime();

        Job(String key, boolean background, long deadline, Supplier<PlatformSearchResult> action) {
            this.key = key;
            this.background = background;
            this.action = action;
            this.deadline = new AtomicLong(deadline);
        }
    }

    private final int capacity;
    private final MeterRegistry meters;
    private final ArrayDeque<Job> foreground = new ArrayDeque<>();
    private final ArrayDeque<Job> background = new ArrayDeque<>();
    private final Map<String, Job> pending = new HashMap<>();
    private final Thread worker;
    private boolean closed;
    private int foregroundRun;

    BaiduRequestDispatcher(int capacity, MeterRegistry meters) {
        if (capacity < 1) throw new IllegalArgumentException("Baidu dispatch queue capacity must be positive");
        this.capacity = capacity;
        this.meters = meters;
        worker = new Thread(this::run, "baidu-outbound");
        worker.setDaemon(true);
        worker.start();
    }

    PlatformSearchResult call(String key, Supplier<PlatformSearchResult> action) {
        long remaining = BaiduCallContext.remainingMillis();
        if (remaining <= 0) return unavailable("LOCAL_QUEUE_TIMEOUT");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining);
        Job job;
        synchronized (this) {
            if (closed) return unavailable("DISPATCHER_CLOSED");
            job = pending.get(key);
            if (job == null) {
                if (foreground.size() + background.size() >= capacity) return unavailable("LOCAL_QUEUE_FULL");
                job = new Job(key, BaiduCallContext.isBackground(), deadline, action);
                pending.put(key, job);
                (job.background ? background : foreground).addLast(job);
                meters.counter("elma.baidu.dispatch", "outcome", "queued").increment();
                notifyAll();
            } else {
                job.deadline.accumulateAndGet(deadline, Math::max);
                if (!BaiduCallContext.isBackground() && background.remove(job)) foreground.addLast(job);
                meters.counter("elma.baidu.dispatch", "outcome", "coalesced").increment();
            }
        }
        try {
            return job.result.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return unavailable("LOCAL_QUEUE_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("CANCELLED");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Baidu dispatch failed", e.getCause());
        }
    }

    private PlatformSearchResult unavailable(String reason) {
        meters.counter("elma.baidu.dispatch", "outcome", reason).increment();
        return PlatformSearchResult.unavailable(reason);
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            synchronized (this) {
                while (!closed && foreground.isEmpty() && background.isEmpty()) {
                    try { wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (closed) return;
                if (!foreground.isEmpty() && (foregroundRun < 3 || background.isEmpty())) {
                    job = foreground.removeFirst();
                    foregroundRun++;
                } else {
                    job = background.removeFirst();
                    foregroundRun = 0;
                }
            }
            meters.timer("elma.baidu.queue.wait").record(System.nanoTime() - job.enqueuedAt, TimeUnit.NANOSECONDS);
            try (var ignored = new BaiduCallContext(job.deadline::get, job.background)) {
                job.result.complete(BaiduCallContext.remainingMillis() <= 0
                        ? unavailable("LOCAL_QUEUE_TIMEOUT") : job.action.get());
            } catch (RuntimeException e) {
                job.result.completeExceptionally(e);
            } finally {
                synchronized (this) { pending.remove(job.key, job); }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            pending.values().forEach(job -> job.result.complete(PlatformSearchResult.unavailable("DISPATCHER_CLOSED")));
            foreground.clear();
            background.clear();
            pending.clear();
            notifyAll();
        }
        worker.interrupt();
        try { worker.join(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
