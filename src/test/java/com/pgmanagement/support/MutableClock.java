package com.pgmanagement.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move forward, so time based rules (lock-outs, expiry, cool-downs) need no sleeping. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start);
        this.zone = zone;
    }

    /** A fixed, reproducible starting point for unit tests. */
    public static MutableClock fixed() {
        return new MutableClock(Instant.parse("2026-11-19T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }

    /** Starts at the real current time, for tests that also touch code using the system clock. */
    public static MutableClock startingNow() {
        return new MutableClock(Instant.now(), ZoneId.systemDefault());
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new MutableClock(now.get(), otherZone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
