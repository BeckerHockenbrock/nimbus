package dev.nimbus.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nimbus.testutil.MutableClock;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ClientRateLimiterTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void decisionsCarryLimitRemainingAndRetryAfter() {
        ClientRateLimiter limiter = new ClientRateLimiter(2, 0.5, clock);

        assertEquals(new RateLimitDecision(true, 2, 1, 0), limiter.tryAcquire("a"));
        assertEquals(new RateLimitDecision(true, 2, 0, 0), limiter.tryAcquire("a"));
        assertEquals(new RateLimitDecision(false, 2, 0, 2), limiter.tryAcquire("a"));

        clock.advance(Duration.ofSeconds(1));
        assertEquals(new RateLimitDecision(false, 2, 0, 1), limiter.tryAcquire("a"), "Retry-After is at least 1");
    }

    @Test
    void clientsAreIndependent() {
        ClientRateLimiter limiter = new ClientRateLimiter(2, 1, clock);
        limiter.tryAcquire("10.0.0.1");
        limiter.tryAcquire("10.0.0.1");

        assertEquals(false, limiter.tryAcquire("10.0.0.1").allowed());
        assertEquals(new RateLimitDecision(true, 2, 1, 0), limiter.tryAcquire("10.0.0.2"));
        assertEquals(2, limiter.bucketCount());
    }

    @Test
    void idleBucketsAreEvictedAndRecreatedFull() {
        ClientRateLimiter limiter = new ClientRateLimiter(3, 1, clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        limiter.tryAcquire("b");

        assertEquals(0, limiter.evictIdle(Duration.ofSeconds(10)), "just used");
        clock.advance(Duration.ofSeconds(10));
        assertEquals(2, limiter.evictIdle(Duration.ofSeconds(10)));
        assertEquals(0, limiter.bucketCount());

        assertEquals(new RateLimitDecision(true, 3, 2, 0), limiter.tryAcquire("a"), "recreated full");
        assertEquals(1, limiter.bucketCount());
    }

    @Test
    void aBucketThatIsNotFullIsKeptEvenWhenIdle() {
        ClientRateLimiter limiter = new ClientRateLimiter(3, 0.01, clock);
        limiter.tryAcquire("a");
        clock.advance(Duration.ofSeconds(30));

        assertEquals(0, limiter.evictIdle(Duration.ofSeconds(10)), "evicting it would hand back a token");
        assertEquals(1, limiter.bucketCount());
    }
}
