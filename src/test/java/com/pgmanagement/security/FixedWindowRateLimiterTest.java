package com.pgmanagement.security;

import com.pgmanagement.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String KEY = "signin|10.0.0.1";

    private final MutableClock clock = MutableClock.fixed();
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, 100);

    @Test
    void allowsRequestsUpToTheLimitThenReportsTheWaitingTime() {
        assertThat(limiter.tryAcquire(KEY, 2, WINDOW)).isZero();
        assertThat(limiter.tryAcquire(KEY, 2, WINDOW)).isZero();

        assertThat(limiter.tryAcquire(KEY, 2, WINDOW)).isEqualTo(60);
    }

    @Test
    void waitingTimeShrinksAsTheWindowPassesAndIsRoundedUp() {
        limiter.tryAcquire(KEY, 1, WINDOW);
        clock.advance(Duration.ofMillis(30_500));

        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isEqualTo(30);

        clock.advance(Duration.ofMillis(29_400));
        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isEqualTo(1);
    }

    @Test
    void aNewWindowStartsOnceTheOldOneIsOver() {
        limiter.tryAcquire(KEY, 1, WINDOW);
        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isPositive();

        clock.advance(WINDOW);

        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isZero();
        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isPositive();
    }

    @Test
    void everyKeyHasItsOwnBudget() {
        limiter.tryAcquire(KEY, 1, WINDOW);

        assertThat(limiter.tryAcquire("signin|10.0.0.2", 1, WINDOW)).isZero();
        assertThat(limiter.tryAcquire(KEY, 1, WINDOW)).isPositive();
    }

    @Test
    void finishedWindowsAreDroppedWhenTheMapIsFull() {
        FixedWindowRateLimiter small = new FixedWindowRateLimiter(clock, 3);
        small.tryAcquire("a", 5, WINDOW);
        small.tryAcquire("b", 5, WINDOW);
        small.tryAcquire("c", 5, WINDOW);
        assertThat(small.size()).isEqualTo(3);

        clock.advance(WINDOW);
        small.tryAcquire("d", 5, WINDOW);

        assertThat(small.size()).isEqualTo(1);
    }

    @Test
    void aFloodOfDistinctKeysCannotGrowTheMapBeyondItsLimit() {
        FixedWindowRateLimiter small = new FixedWindowRateLimiter(clock, 3);
        for (int i = 0; i < 50; i++) {
            assertThat(small.tryAcquire("client-" + i, 5, WINDOW)).isZero();
        }

        assertThat(small.size()).isLessThanOrEqualTo(3);
    }
}
