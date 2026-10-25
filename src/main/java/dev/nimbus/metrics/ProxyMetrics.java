package dev.nimbus.metrics;

import dev.nimbus.cache.Cache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.ratelimit.UpstreamBudget;
import dev.nimbus.upstream.UpstreamException;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Request outcomes, upstream counters, and latency recorders (PLAN.md, Section 3.6).
 *
 * <p>Every proxied request (anything but {@code /metrics} and {@code /health}) gets exactly one
 * {@link #recordOutcome}. Upstream counters are updated only at the single place {@code UpstreamClient.fetch} is
 * called.
 */
public final class ProxyMetrics {

    private final LongAdder requestsTotal = new LongAdder();
    private final Map<Outcome, LongAdder> outcomes = new EnumMap<>(Outcome.class);

    private final LongAdder upstreamCalls = new LongAdder();
    private final LongAdder upstreamResponses = new LongAdder();
    private final LongAdder upstream429 = new LongAdder();
    private final LongAdder upstream5xx = new LongAdder();
    private final LongAdder failuresTimeout = new LongAdder();
    private final LongAdder failuresConnection = new LongAdder();
    private final LongAdder failuresTooLarge = new LongAdder();
    private final LongAdder skippedBudget = new LongAdder();
    private final LongAdder skippedCooldown = new LongAdder();
    private final LongAdder skippedNoPermit = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();

    private final LatencyRecorder all;
    private final LatencyRecorder hit;
    private final LatencyRecorder miss;
    private final LatencyRecorder stale;
    private final LatencyRecorder upstream;

    private final Cache<?, ?> cache;
    private final UpstreamBudget budget;
    private final int maxEntries;
    private final long maxBytes;
    private final String cacheImpl;
    private final InstantSource clock;
    private final Instant started;

    public ProxyMetrics(NimbusConfig config, Cache<?, ?> cache, UpstreamBudget budget, InstantSource clock) {
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, new LongAdder());
        }
        int window = config.metricsLatencyWindow();
        all = new LatencyRecorder(window);
        hit = new LatencyRecorder(window);
        miss = new LatencyRecorder(window);
        stale = new LatencyRecorder(window);
        upstream = new LatencyRecorder(window);
        this.cache = cache;
        this.budget = budget;
        this.maxEntries = config.cacheMaxEntries();
        this.maxBytes = config.cacheMaxBytes();
        this.cacheImpl = config.cacheImpl();
        this.clock = clock;
        this.started = clock.instant();
    }

    // --- requests ---

    /** Counts a finished proxied request. Call exactly once per request, before returning its response. */
    public void recordOutcome(Outcome outcome) {
        requestsTotal.increment();
        outcomes.get(outcome).increment();
    }

    /** Server latency of a proxied request, recorded after its response was written. */
    public void recordLatency(Outcome outcome, long nanos) {
        all.record(nanos);
        switch (outcome) {
            case HIT -> hit.record(nanos);
            case MISS -> miss.record(nanos);
            case STALE_WITHOUT_UPSTREAM, STALE_AFTER_UPSTREAM_FAILURE -> stale.record(nanos);
            default -> {
                // Only in "all".
            }
        }
    }

    // --- upstream (only from the single fetch call site) ---

    /** An HTTP request is about to be sent upstream. Pair with {@link #upstreamFinished}. */
    public void upstreamStarted() {
        upstreamCalls.increment();
        inFlight.incrementAndGet();
    }

    /** The upstream call is over (in a {@code finally}); records its latency, success or not. */
    public void upstreamFinished(long nanos) {
        inFlight.decrementAndGet();
        upstream.record(nanos);
    }

    public void upstreamResponse(int status) {
        upstreamResponses.increment();
        if (status == 429) {
            upstream429.increment();
        } else if (status >= 500) {
            upstream5xx.increment();
        }
    }

    /** {@code INTERRUPTED} counts as a connection failure, so that calls == responses + failures still holds. */
    public void upstreamFailure(UpstreamException.Kind kind) {
        switch (kind) {
            case TIMEOUT -> failuresTimeout.increment();
            case TOO_LARGE -> failuresTooLarge.increment();
            case CONNECTION, INTERRUPTED -> failuresConnection.increment();
        }
    }

    /** A request needed the upstream but the budget or a cooldown stopped it. */
    public void upstreamSkipped(UpstreamBudget.SkipReason reason) {
        switch (reason) {
            case BUDGET -> skippedBudget.increment();
            case COOLDOWN -> skippedCooldown.increment();
        }
    }

    /** A request needed the upstream but no concurrency permit freed up within {@code permitWaitMs}. */
    public void upstreamSkippedNoPermit() {
        skippedNoPermit.increment();
    }

    // --- snapshot ---

    public MetricsSnapshot snapshot() {
        Map<Outcome, Long> counts = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, outcomes.get(outcome).sum());
        }
        long hits = counts.get(Outcome.HIT);
        long coalesced = counts.get(Outcome.COALESCED);
        long staleWithout = counts.get(Outcome.STALE_WITHOUT_UPSTREAM);
        long staleAfter = counts.get(Outcome.STALE_AFTER_UPSTREAM_FAILURE);
        long lookups = hits + coalesced + staleWithout + counts.get(Outcome.MISS) + staleAfter
                + counts.get(Outcome.UPSTREAM_ERROR) + counts.get(Outcome.UNAVAILABLE);
        long saved = hits + coalesced + staleWithout;

        return new MetricsSnapshot(
                Math.max(0, Duration.between(started, clock.instant()).toSeconds()),
                new MetricsSnapshot.Requests(requestsTotal.sum(), counts),
                new MetricsSnapshot.Derived(lookups, saved, staleWithout + staleAfter,
                        ratio(hits, lookups), ratio(saved, lookups)),
                new MetricsSnapshot.Upstream(upstreamCalls.sum(), upstreamResponses.sum(), upstream429.sum(),
                        upstream5xx.sum(),
                        new MetricsSnapshot.Failures(failuresTimeout.sum(), failuresConnection.sum(),
                                failuresTooLarge.sum()),
                        new MetricsSnapshot.Skipped(skippedBudget.sum(), skippedCooldown.sum(), skippedNoPermit.sum()),
                        budget.cooldownActive(),
                        inFlight.get()),
                new MetricsSnapshot.CacheStats(cache.size(), maxEntries, cache.weightedSize(), maxBytes,
                        cache.evictionCount(), cacheImpl),
                new MetricsSnapshot.LatencyGroups(MetricsSnapshot.Latency.of(all.snapshot()),
                        MetricsSnapshot.Latency.of(hit.snapshot()), MetricsSnapshot.Latency.of(miss.snapshot()),
                        MetricsSnapshot.Latency.of(stale.snapshot()), MetricsSnapshot.Latency.of(upstream.snapshot())));
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
