package com.premisave.developer.security;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed one minute window per key, held in memory. With several instances each one counts separately, so the
 * effective ceiling is the setting times the number of instances.
 */
public final class RateLimiter {

    /** The outcome of one attempt. */
    public record Decision(boolean allowed, int remaining, int retryAfterSeconds) {}

    private static final class Window {
        long minute;
        int count;
    }

    private final int perMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private long lastSweepMinute;

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
    }

    public Decision tryAcquire(String key, long nowMillis) {
        long minute = nowMillis / 60_000;
        sweep(minute);
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (w.minute != minute) {
                w.minute = minute;
                w.count = 0;
            }
            if (w.count >= perMinute) {
                int retry = (int) Math.max(1, (minute + 1) * 60 - nowMillis / 1000);
                return new Decision(false, 0, retry);
            }
            w.count++;
            return new Decision(true, perMinute - w.count, 0);
        }
    }

    private synchronized void sweep(long minute) {
        if (minute == lastSweepMinute) return;
        lastSweepMinute = minute;
        windows.entrySet().removeIf(e -> e.getValue().minute < minute - 1);
    }
}
