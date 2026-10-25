package dev.nimbus.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A token bucket that starts full, holds at most {@code capacity} tokens, and refills continuously at
 * {@code refillPerSecond}.
 *
 * <p>Refill is lazy: each call first adds the tokens earned since the last refill. A clock that goes backwards (NTP,
 * laptop sleep) adds nothing, so it can never mint tokens (PLAN.md, Section 9, row 19). The lock guards only a few
 * arithmetic operations and is never held during I/O.
 */
public final class TokenBucket {

    private final int capacity;
    private final double refillPerSecond;
    private final InstantSource clock;
    private final ReentrantLock lock = new ReentrantLock();
    private double tokens;
    private Instant lastRefill;
    private Instant lastUsed;

    public TokenBucket(int capacity, double refillPerSecond, InstantSource clock) {
        validate(capacity, refillPerSecond);
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
        this.tokens = capacity;
        this.lastRefill = clock.instant();
        this.lastUsed = lastRefill;
    }

    static void validate(int capacity, double refillPerSecond) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (!(refillPerSecond > 0) || Double.isInfinite(refillPerSecond)) {
            throw new IllegalArgumentException("refillPerSecond must be a positive number, got " + refillPerSecond);
        }
    }

    /** Takes one token if a whole token is available. */
    public boolean tryAcquire() {
        lock.lock();
        try {
            Instant now = refill();
            lastUsed = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Tokens available now, including fractions. */
    public double availableTokens() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }

    /** Whole seconds, rounded up, until a whole token is available; 0 if one is available now. */
    public long secondsUntilNextToken() {
        lock.lock();
        try {
            refill();
            return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / refillPerSecond);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Whether the bucket is full and has not been used for at least {@code idle}. Dropping such a bucket and later
     * recreating it full changes nothing for its client.
     */
    boolean isIdle(Duration idle) {
        lock.lock();
        try {
            Instant now = refill();
            return tokens >= capacity && !now.isBefore(lastUsed.plus(idle));
        } finally {
            lock.unlock();
        }
    }

    /** Adds the tokens earned since the last refill and returns the current time. Call with the lock held. */
    private Instant refill() {
        Instant now = clock.instant();
        if (now.isAfter(lastRefill)) {
            double elapsedSeconds = Duration.between(lastRefill, now).toNanos() / 1e9;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefill = now;
        }
        // A clock that went backwards adds nothing; lastRefill stays put until time catches up.
        return now;
    }
}
