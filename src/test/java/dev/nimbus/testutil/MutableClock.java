package dev.nimbus.testutil;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock that only moves when a test calls {@link #advance}. */
public final class MutableClock implements InstantSource {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public MutableClock() {
        this(Instant.parse("2026-10-22T12:00:00Z"));
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }
}
