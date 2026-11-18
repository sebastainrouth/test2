package com.schembuilder.schem;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Measures how fast blocks are being placed over a sliding window and turns that into an ETA.
 * Pure Java (no Minecraft classes) so it can be tested on its own.
 *
 * <p>The rate is blocks gained across the window divided by the time it covers, so a stall makes the
 * rate (and the ETA) fall back to "unknown" instead of promising a stale number.
 */
public final class ProgressTracker {
    /** Need at least this much history before trusting a rate. */
    private static final long MIN_SPAN_MS = 2000;

    private final long windowMs;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {timeMs, placedCount}

    public ProgressTracker(long windowMs) {
        this.windowMs = windowMs;
    }

    public void reset() {
        samples.clear();
    }

    public void sample(long nowMs, long placed) {
        samples.addLast(new long[]{nowMs, placed});
        // Drop old samples, but keep the newest one that is at/before the cutoff so the span covers the window.
        long cutoff = nowMs - windowMs;
        while (samples.size() > 1) {
            Iterator<long[]> it = samples.iterator();
            it.next();
            long[] second = it.next();
            if (second[0] <= cutoff) samples.pollFirst();
            else break;
        }
    }

    /** Blocks placed per second over the window, or 0 if there isn't enough data or nothing was placed. */
    public double perSecond() {
        if (samples.size() < 2) return 0;
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long span = last[0] - first[0];
        if (span < MIN_SPAN_MS) return 0;
        long gained = last[1] - first[1];
        return gained <= 0 ? 0 : gained * 1000.0 / span;
    }

    /** Seconds until {@code remaining} blocks are placed at the current rate, or -1 if unknown. */
    public long etaSeconds(long remaining) {
        if (remaining <= 0) return 0;
        double rate = perSecond();
        return rate <= 0 ? -1 : (long) Math.ceil(remaining / rate);
    }

    public static String format(long seconds) {
        if (seconds < 0) return "unknown";
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
