package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.config.AmapProperties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/**
 * 进程级平滑限速器。所有高德首次请求和重试共享同一个实例；许可发放使用 CAS，
 * 实际等待发生在锁外，避免一个等待线程持有全局锁。
 */
@Component
public class AmapRateLimiter {

    private final long intervalNanos;
    private final AtomicLong nextPermitNanos = new AtomicLong();

    public AmapRateLimiter(AmapProperties properties) {
        int permitsPerSecond = Math.max(1, properties.getRateLimitPerSecond());
        long intervalMillis = (1000L + permitsPerSecond - 1) / permitsPerSecond;
        intervalNanos = intervalMillis * 1_000_000L;
    }

    /** 在 deadline 前取得许可；不能取得时不占用未来时隙。 */
    public Permit acquire(long deadlineNanos) {
        long started = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            long next = nextPermitNanos.get();
            if (now > deadlineNanos || next > deadlineNanos) {
                return new Permit(false, elapsedMillis(started));
            }
            if (now < next) {
                LockSupport.parkNanos(next - now);
                if (Thread.currentThread().isInterrupted()) {
                    return new Permit(false, elapsedMillis(started));
                }
                continue;
            }
            if (nextPermitNanos.compareAndSet(next, now + intervalNanos)) {
                return new Permit(true, elapsedMillis(started));
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record Permit(boolean acquired, long waitedMillis) {
    }
}
