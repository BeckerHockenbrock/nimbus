package dev.nimbus.metrics;

import dev.nimbus.proxy.Outcome;

import java.util.Map;

/**
 * A point-in-time copy of the metrics, shaped like the {@code GET /metrics} JSON (PLAN.md, Section 3.6). Gson
 * serializes it on the server and deserializes it in the GUI.
 *
 * <p>Reads across counters aren't atomic: while requests are in flight a snapshot may be off by those requests.
 * The invariants hold exactly once traffic stops.
 */
public record MetricsSnapshot(long uptimeSeconds, Requests requests, Derived derived, Upstream upstream,
                              CacheStats cache, LatencyGroups latencyMs) {

    /** {@code outcomes} has every {@link Outcome}, in declaration order. */
    public record Requests(long total, Map<Outcome, Long> outcomes) {
    }

    /** Ratios are {@code null} when {@code lookups == 0}. */
    public record Derived(long lookups, long upstreamCallsSaved, long staleTotal, Double cacheHitRatio,
                          Double upstreamAvoidanceRatio) {
    }

    public record Upstream(long calls, long responses, long status429, long status5xx, Failures failures,
                           Skipped skipped, boolean cooldownActive, int inFlight) {
    }

    public record Failures(long timeout, long connection, long tooLarge) {
    }

    public record Skipped(long budget, long cooldown, long noPermit) {
    }

    public record CacheStats(int entries, int maxEntries, long bytes, long maxBytes, long evictions, String impl) {
    }

    public record LatencyGroups(Latency all, Latency hit, Latency miss, Latency stale, Latency upstream) {
    }

    /** Milliseconds with 3 decimals; the percentile fields are {@code null} when the window is empty. */
    public record Latency(int count, long totalRecorded, Double p50, Double p95, Double p99, Double max) {

        static Latency of(LatencyRecorder.Snapshot s) {
            return new Latency(s.count(), s.totalRecorded(), millis(s.p50()), millis(s.p95()), millis(s.p99()),
                    millis(s.max()));
        }

        private static Double millis(Long nanos) {
            return nanos == null ? null : Math.round(nanos / 1_000.0) / 1_000.0;
        }
    }

    public long outcome(Outcome outcome) {
        return requests.outcomes().getOrDefault(outcome, 0L);
    }
}
