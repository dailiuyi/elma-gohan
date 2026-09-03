package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.BaiduProperties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/**
 * 百度 Place 进程级平滑限速器。通用分页、逐店检索、suggestion 与 V2
 * 共享同一个 Spring 单例，避免并发推荐分别耗尽上游调用额度。
 */
@Component
public class BaiduPlaceRateLimiter {

    private final long intervalNanos;
    private final long maxWaitNanos;
    private final AtomicLong nextPermitNanos = new AtomicLong();

    public BaiduPlaceRateLimiter(BaiduProperties properties) {
        int permitsPerSecond = Math.max(1, properties.getRateLimitPerSecond());
        long intervalMillis = (1000L + permitsPerSecond - 1) / permitsPerSecond;
        intervalNanos = intervalMillis * 1_000_000L;
        maxWaitNanos = Math.max(0L, properties.getRateLimitMaxWaitMs()) * 1_000_000L;
    }

    /** 在最大排队时间内取得许可；失败时不占用未来时隙。 */
    public Permit acquire() {
        long started = System.nanoTime();
        long deadline = started + maxWaitNanos;
        while (true) {
            long now = System.nanoTime();
            long next = nextPermitNanos.get();
            if (now >= next) {
                if (nextPermitNanos.compareAndSet(next, now + intervalNanos)) {
                    return new Permit(true, elapsedMillis(started));
                }
                continue;
            }
            if (next > deadline) {
                return new Permit(false, elapsedMillis(started));
            }
            LockSupport.parkNanos(next - now);
            if (Thread.currentThread().isInterrupted()) {
                return new Permit(false, elapsedMillis(started));
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public record Permit(boolean acquired, long waitedMillis) {
    }
}
