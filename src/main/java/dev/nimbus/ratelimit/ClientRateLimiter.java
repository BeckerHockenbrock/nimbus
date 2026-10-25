package dev.nimbus.ratelimit;

import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link TokenBucket} per client.
 *
 * <p>The client ID is the socket address. {@code X-Forwarded-For} is deliberately ignored because clients can forge
 * it (PLAN.md, Section 9, row 13). Limits apply to every proxied request, hits included: they protect the proxy
 * itself, while the {@link UpstreamBudget} protects the upstream.
 */
public final class ClientRateLimiter {

    private final int capacity;
    private final double refillPerSecond;
    private final InstantSource clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public ClientRateLimiter(int capacity, double refillPerSecond, InstantSource clock) {
        TokenBucket.validate(capacity, refillPerSecond);
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    public RateLimitDecision tryAcquire(String clientId) {
        TokenBucket bucket = buckets.computeIfAbsent(clientId,
                id -> new TokenBucket(capacity, refillPerSecond, clock));
        boolean allowed = bucket.tryAcquire();
        long remaining = (long) Math.floor(bucket.availableTokens());
        long retryAfter = allowed ? 0 : Math.max(1, bucket.secondsUntilNextToken());
        return new RateLimitDecision(allowed, capacity, remaining, retryAfter);
    }

    /**
     * Drops buckets that are full and unused for at least {@code idle}, so memory doesn't grow with every client
     * ever seen. Only full buckets are dropped, so being evicted and recreated full changes nothing for a client.
     * (A request racing with the eviction may spend a token from the dropped bucket, a harmless burst of one.)
     *
     * @return the number of buckets removed
     */
    public int evictIdle(Duration idle) {
        int removed = 0;
        for (var entry : buckets.entrySet()) {
            if (entry.getValue().isIdle(idle) && buckets.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    /** The number of tracked clients. */
    public int bucketCount() {
        return buckets.size();
    }

    public int capacity() {
        return capacity;
    }
}
