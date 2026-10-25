package dev.nimbus.ratelimit;

import dev.nimbus.config.NimbusConfig;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The global upstream call budget: minute, hour, and day {@link TokenBucket}s (configured at 80% of Open-Meteo's
 * limits), plus a cooldown after the upstream answers 429.
 *
 * <p>A call takes one token from <em>all</em> buckets or from none: the check and the take happen under one lock, so
 * an empty day bucket never drains the minute bucket. The token is spent <em>before</em> the call, even if the call
 * then fails, because Open-Meteo counts failed calls too. Only real upstream calls spend budget; cache hits don't.
 */
public final class UpstreamBudget {

    /** Why an upstream call was skipped. */
    public enum SkipReason { BUDGET, COOLDOWN }

    /**
     * The result of {@link #tryAcquire()}.
     *
     * @param skipReason        {@code null} when granted
     * @param retryAfterSeconds when denied, whole seconds (at least 1) until a call could be allowed; otherwise 0
     */
    public record Decision(SkipReason skipReason, long retryAfterSeconds) {

        static final Decision GRANTED = new Decision(null, 0);

        public boolean granted() {
            return skipReason == null;
        }
    }

    private final List<TokenBucket> buckets;
    private final InstantSource clock;
    private final ReentrantLock lock = new ReentrantLock();
    private Instant cooldownUntil = Instant.MIN;

    public UpstreamBudget(int perMinute, int perHour, int perDay, InstantSource clock) {
        this.buckets = List.of(
                new TokenBucket(perMinute, perMinute / 60.0, clock),
                new TokenBucket(perHour, perHour / 3_600.0, clock),
                new TokenBucket(perDay, perDay / 86_400.0, clock));
        this.clock = clock;
    }

    public static UpstreamBudget from(NimbusConfig config, InstantSource clock) {
        return new UpstreamBudget(config.upstreamPerMinute(), config.upstreamPerHour(), config.upstreamPerDay(), clock);
    }

    /** Takes one token from every bucket, or none if a cooldown is active or any bucket is empty. */
    public Decision tryAcquire() {
        lock.lock();
        try {
            Instant now = clock.instant();
            if (now.isBefore(cooldownUntil)) {
                return new Decision(SkipReason.COOLDOWN, ceilSeconds(Duration.between(now, cooldownUntil)));
            }
            long wait = 0;
            for (TokenBucket bucket : buckets) {
                wait = Math.max(wait, bucket.secondsUntilNextToken());
            }
            if (wait > 0) {
                return new Decision(SkipReason.BUDGET, wait);
            }
            // Every bucket has a token, and only this lock's holder takes from them, so each take succeeds.
            for (TokenBucket bucket : buckets) {
                bucket.tryAcquire();
            }
            return Decision.GRANTED;
        } finally {
            lock.unlock();
        }
    }

    /** Pauses upstream calls for {@code duration} from now. A longer cooldown already running is kept. */
    public void startCooldown(Duration duration) {
        lock.lock();
        try {
            Instant until = clock.instant().plus(duration);
            if (until.isAfter(cooldownUntil)) {
                cooldownUntil = until;
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean cooldownActive() {
        lock.lock();
        try {
            return clock.instant().isBefore(cooldownUntil);
        } finally {
            lock.unlock();
        }
    }

    /** Whole tokens left in the minute, hour, and day buckets, in that order. For tests and diagnostics. */
    public List<Long> remaining() {
        lock.lock();
        try {
            return buckets.stream().map(b -> (long) Math.floor(b.availableTokens())).toList();
        } finally {
            lock.unlock();
        }
    }

    /** A positive duration in whole seconds, rounded up. */
    private static long ceilSeconds(Duration d) {
        return Math.max(1, d.getNano() == 0 ? d.getSeconds() : d.getSeconds() + 1);
    }
}
