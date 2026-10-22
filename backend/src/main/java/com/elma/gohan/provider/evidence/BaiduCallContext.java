package com.elma.gohan.provider.evidence;

import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request deadline propagated through synchronous recall and its HTTP calls. */
public final class BaiduCallContext implements AutoCloseable {
    private record Context(LongSupplier deadline, boolean background, AtomicBoolean outboundFailure) { }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private final Context previous;
    public BaiduCallContext(int budgetMs) {
        this(budgetMs, isBackground());
    }
    public BaiduCallContext(int budgetMs, boolean background) {
        previous = CURRENT.get();
        long requested = System.nanoTime() + Math.max(1, budgetMs) * 1_000_000L;
        CURRENT.set(new Context(() -> previous == null ? requested
                : Math.min(previous.deadline().getAsLong(), requested), background,
                previous == null ? new AtomicBoolean() : previous.outboundFailure()));
    }
    BaiduCallContext(LongSupplier deadline, boolean background, AtomicBoolean outboundFailure) {
        previous = CURRENT.get();
        CURRENT.set(new Context(deadline, background, outboundFailure));
    }
    public static void recordOutboundFailure() {
        Context context = CURRENT.get();
        if (context != null) context.outboundFailure().set(true);
    }
    public boolean hasOutboundFailure() {
        return CURRENT.get().outboundFailure().get();
    }
    public static boolean isBackground() {
        Context context = CURRENT.get();
        return context != null && context.background();
    }
    public static long remainingMillis() {
        Context context = CURRENT.get();
        return context == null ? 8000 : Math.max(0,
                (context.deadline().getAsLong() - System.nanoTime()) / 1_000_000);
    }
    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
