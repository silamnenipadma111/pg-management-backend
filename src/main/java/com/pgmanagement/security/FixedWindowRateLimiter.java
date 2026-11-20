package com.pgmanagement.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory request counter (fixed window per key). Good enough for a single application instance;
 * use a shared store (e.g. Redis) if the API is ever scaled out to several instances.
 */
public class FixedWindowRateLimiter {

    private record Window(long endsAtMillis, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    public FixedWindowRateLimiter(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /**
     * Counts one request for the key.
     *
     * @return 0 when the request is allowed, otherwise the number of seconds until the window ends
     */
    public long tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() >= maxEntries) {
            evict(now);
        }
        Window current = windows.compute(key, (k, old) -> old == null || old.endsAtMillis() <= now
                ? new Window(now + window.toMillis(), 1)
                : new Window(old.endsAtMillis(), old.count() + 1));
        if (current.count() <= limit) {
            return 0;
        }
        return Math.max(1, (current.endsAtMillis() - now + 999) / 1000);
    }

    int size() {
        return windows.size();
    }

    /** Drops finished windows; if the map is still full (a flood of distinct keys) it is reset to bound memory. */
    private void evict(long now) {
        windows.values().removeIf(w -> w.endsAtMillis() <= now);
        if (windows.size() >= maxEntries) {
            windows.clear();
        }
    }
}
