package dev.nimbus.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.testutil.MutableClock;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    private final MutableClock clock = new MutableClock();

    private static void drain(TokenBucket bucket) {
        while (bucket.tryAcquire()) {
            // Take every whole token.
        }
    }

    @Test
    void burstUpToCapacityThenDeny() {
        TokenBucket bucket = new TokenBucket(5, 10, clock);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "token " + (i + 1));
        }
        assertFalse(bucket.tryAcquire());
        assertEquals(0.0, bucket.availableTokens());
    }

    @Test
    void refillsOneTokenAfter100MsAt10PerSecond() {
        TokenBucket bucket = new TokenBucket(5, 10, clock);
        drain(bucket);

        clock.advance(Duration.ofMillis(50));
        assertFalse(bucket.tryAcquire(), "half a token is not enough");
        assertEquals(0.5, bucket.availableTokens(), 1e-9);
        clock.advance(Duration.ofMillis(50));
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void tokensNeverExceedCapacity() {
        TokenBucket bucket = new TokenBucket(3, 10, clock);
        bucket.tryAcquire();

        clock.advance(Duration.ofHours(1));

        assertEquals(3.0, bucket.availableTokens());
        drain(bucket);
        assertEquals(0.0, bucket.availableTokens());
    }

    @Test
    void secondsUntilNextTokenRoundsUp() {
        TokenBucket bucket = new TokenBucket(1, 0.25, clock);
        assertEquals(0, bucket.secondsUntilNextToken(), "a token is available now");
        bucket.tryAcquire();

        assertEquals(4, bucket.secondsUntilNextToken());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(3, bucket.secondsUntilNextToken());
        clock.advance(Duration.ofMillis(2_500));
        assertEquals(1, bucket.secondsUntilNextToken(), "0.5 s left rounds up to 1");
        clock.advance(Duration.ofMillis(500));
        assertEquals(0, bucket.secondsUntilNextToken());
    }

    @Test
    void clockGoingBackwardsAddsNoTokens() {
        Instant start = clock.instant();
        TokenBucket bucket = new TokenBucket(5, 10, clock);
        drain(bucket);

        clock.set(start.minus(Duration.ofHours(1)));
        assertEquals(0.0, bucket.availableTokens());
        assertFalse(bucket.tryAcquire());

        clock.set(start.plus(Duration.ofMillis(100)));
        assertTrue(bucket.tryAcquire(), "time since the last refill counts once the clock catches up");
        assertFalse(bucket.tryAcquire(), "the backwards jump earned nothing");
    }

    @Test
    void idleMeansFullAndUnusedForTheWholeDuration() {
        TokenBucket bucket = new TokenBucket(2, 1, clock);
        bucket.tryAcquire();

        clock.advance(Duration.ofSeconds(5));
        assertFalse(bucket.isIdle(Duration.ofSeconds(10)), "full, but used 5 s ago");
        clock.advance(Duration.ofSeconds(5));
        assertTrue(bucket.isIdle(Duration.ofSeconds(10)));

        TokenBucket slow = new TokenBucket(2, 0.01, clock);
        slow.tryAcquire();
        clock.advance(Duration.ofSeconds(60));
        assertFalse(slow.isIdle(Duration.ofSeconds(10)), "not yet refilled, so dropping it would grant tokens");
    }

    @Test
    void invalidArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0, clock));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, Double.NaN, clock));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, Double.POSITIVE_INFINITY, clock));
    }
}
