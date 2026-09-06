package com.elma.gohan.provider.evidence;

/** Request deadline propagated through synchronous recall and its HTTP calls. */
public final class BaiduCallContext implements AutoCloseable {
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Long previous;
    public BaiduCallContext(int budgetMs) {
        previous = DEADLINE.get();
        long requested = System.nanoTime() + Math.max(1, budgetMs) * 1_000_000L;
        DEADLINE.set(previous == null ? requested : Math.min(previous, requested));
    }
    public static long remainingMillis() {
        Long deadline = DEADLINE.get();
        return deadline == null ? 8000 : Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
    }
    @Override public void close() {
        if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous);
    }
}
