package com.pgmanagement.security;

import com.pgmanagement.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection: after too many wrong passwords for one email address, sign-in for that address is
 * locked for a while. Unknown addresses are tracked exactly like existing accounts, so the lock does not reveal
 * whether an account exists.
 */
@Service
public class LoginAttemptService {

    private record Attempts(int failures, long windowEndsAt, long lockedUntil) {
    }

    private static final int MAX_TRACKED = 50_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxFailures;
    private final long lockMillis;

    public LoginAttemptService(AppProperties appProperties, Clock clock) {
        this.clock = clock;
        this.maxFailures = Math.max(1, appProperties.auth().maxFailedAttempts());
        this.lockMillis = appProperties.auth().lockDuration().toMillis();
    }

    /** @return seconds until sign-in is allowed again, 0 when the address is not locked */
    public long secondsUntilUnlocked(String email) {
        Attempts a = attempts.get(key(email));
        long now = clock.millis();
        if (a == null || a.lockedUntil() <= now) {
            return 0;
        }
        return Math.max(1, (a.lockedUntil() - now + 999) / 1000);
    }

    public void recordFailure(String email) {
        long now = clock.millis();
        if (attempts.size() >= MAX_TRACKED) {
            attempts.values().removeIf(a -> a.windowEndsAt() <= now && a.lockedUntil() <= now);
        }
        attempts.compute(key(email), (k, old) -> {
            if (old != null && old.lockedUntil() > now) {
                return old;
            }
            if (old == null || old.windowEndsAt() <= now) {
                return next(1, now + lockMillis, now);
            }
            return next(old.failures() + 1, old.windowEndsAt(), now);
        });
    }

    public void recordSuccess(String email) {
        attempts.remove(key(email));
    }

    private Attempts next(int failures, long windowEndsAt, long now) {
        if (failures >= maxFailures) {
            // locked: the counter starts again once the lock is over
            return new Attempts(0, now + lockMillis, now + lockMillis);
        }
        return new Attempts(failures, windowEndsAt, 0);
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
