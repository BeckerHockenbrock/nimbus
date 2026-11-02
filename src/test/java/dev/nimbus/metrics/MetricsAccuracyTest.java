package dev.nimbus.metrics;

import static dev.nimbus.testutil.TestEntries.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.proxy.ProxyRequest;
import dev.nimbus.proxy.ProxyResponse;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.MutableClock;
import dev.nimbus.testutil.ProxyFixture;
import dev.nimbus.upstream.UpstreamException;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exact metric counts for scripted request sequences (PLAN.md, Section 3.6). */
class MetricsAccuracyTest {

    private static final String BERLIN = "latitude=52.52437&longitude=13.41053&current=temperature_2m";

    private final MutableClock clock = new MutableClock();
    private final FakeUpstreamClient upstream = new FakeUpstreamClient();
    private NimbusConfig config = NimbusConfig.defaults();
    private ProxyFixture fixture = ProxyFixture.create(config, upstream, clock);
    private ProxyMetrics metrics = fixture.metrics();

    /** Rebuilds the service with {@code overrides} on top of the defaults. */
    private void configure(Map<String, String> overrides) {
        config = NimbusConfig.withOverrides(overrides);
        fixture = ProxyFixture.create(config, upstream, clock);
        metrics = fixture.metrics();
    }

    private ProxyResponse get(String path, String query) {
        return fixture.service().handle(new ProxyRequest("GET", path, query, "127.0.0.1"));
    }

    private static Map<Outcome, Long> expected(Object... outcomeCounts) {
        Map<Outcome, Long> map = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            map.put(outcome, 0L);
        }
        for (int i = 0; i < outcomeCounts.length; i += 2) {
            map.put((Outcome) outcomeCounts[i], ((Number) outcomeCounts[i + 1]).longValue());
        }
        return map;
    }

    /** I1–I3 (and I5). {@code internalAfterCall} is the number of injected bugs that struck after an upstream call. */
    static void assertInvariants(MetricsSnapshot s, long internalAfterCall, long maxEntries, long maxBytes) {
        long sum = s.requests().outcomes().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(s.requests().total(), sum, "I1: total == sum of outcomes");
        assertEquals(s.upstream().calls(), s.outcome(Outcome.MISS) + s.outcome(Outcome.STALE_AFTER_UPSTREAM_FAILURE)
                + s.outcome(Outcome.UPSTREAM_ERROR) + internalAfterCall, "I2: calls == outcomes that called upstream");
        MetricsSnapshot.Failures f = s.upstream().failures();
        assertEquals(s.upstream().calls() - internalAfterCall,
                s.upstream().responses() + f.timeout() + f.connection() + f.tooLarge(), "I3: calls == responses + failures");
        assertEquals(0, s.upstream().inFlight());
        assertTrue(s.cache().entries() <= maxEntries && s.cache().bytes() <= maxBytes, "I5");
    }

    @Test
    void freshInstanceHasNullRatios() {
        MetricsSnapshot s = metrics.snapshot();

        assertEquals(0, s.requests().total());
        assertEquals(0, s.derived().lookups());
        assertNull(s.derived().cacheHitRatio());
        assertNull(s.derived().upstreamAvoidanceRatio());
        assertEquals(expected(), s.requests().outcomes());
        assertNull(s.latencyMs().all().p50());
        assertEquals(0, s.uptimeSeconds());
    }

    /** The plan's scripted sequence, in order. A client capacity of 3 makes step 4 rate-limited. */
    @Test
    void scriptedSequenceHasExactCounts() {
        configure(Map.of("nimbus.ratelimit.client.capacity", "3", "nimbus.ratelimit.client.refillPerSecond", "1"));
        upstream.respond(response(200, "{\"ok\":true}", "Content-Type", "application/json"));

        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());            // 1
        assertEquals(Outcome.HIT, get("/v1/forecast", BERLIN).outcome());             // 2
        assertEquals(Outcome.HIT, get("/v1/forecast", BERLIN).outcome());             // 3
        assertEquals(Outcome.RATE_LIMITED, get("/v1/forecast", BERLIN).outcome());    // 4
        clock.advance(Duration.ofSeconds(1));                                          // One token back.
        assertEquals(Outcome.INVALID, get("/v1/forecast", "longitude=13.4").outcome()); // 5: missing latitude
        assertEquals(Outcome.INVALID, get("/v1/nope", BERLIN).outcome());             // 6: 404
        clock.advance(config.forecastTtl());
        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());            // 7: past fresh
        upstream.respond(response(400, "{\"reason\":\"bad\",\"error\":true}"));
        ProxyResponse relayed = get("/v1/forecast", "latitude=999&longitude=0");       // 8: upstream 400
        assertEquals(400, relayed.status());
        assertEquals(Outcome.MISS, relayed.outcome());
        clock.advance(Duration.ofSeconds(42));

        MetricsSnapshot s = metrics.snapshot();

        assertEquals(expected(Outcome.HIT, 2, Outcome.MISS, 3, Outcome.INVALID, 2, Outcome.RATE_LIMITED, 1),
                s.requests().outcomes());
        assertEquals(8, s.requests().total());
        assertEquals(5, s.derived().lookups(), "2 HIT + 3 MISS");
        assertEquals(2, s.derived().upstreamCallsSaved());
        assertEquals(0, s.derived().staleTotal());
        assertEquals(2.0 / 5, s.derived().cacheHitRatio());
        assertEquals(2.0 / 5, s.derived().upstreamAvoidanceRatio());
        assertEquals(3, s.upstream().calls());
        assertEquals(3, s.upstream().responses());
        assertEquals(0, s.upstream().status429());
        assertEquals(0, s.upstream().status5xx());
        assertEquals(new MetricsSnapshot.Failures(0, 0, 0), s.upstream().failures());
        assertEquals(new MetricsSnapshot.Skipped(0, 0, 0), s.upstream().skipped());
        assertEquals(3, s.latencyMs().upstream().count(), "one upstream sample per call");
        assertEquals(0, s.latencyMs().all().count(), "server latency is recorded by ProxyHandler, not here");
        assertEquals(1, s.cache().entries());
        assertEquals(config.cacheMaxEntries(), s.cache().maxEntries());
        assertEquals(config.cacheMaxBytes(), s.cache().maxBytes());
        assertEquals("linkedhashmap", s.cache().impl());
        assertEquals(config.forecastTtl().plusSeconds(1 + 42).toSeconds(), s.uptimeSeconds());
        assertInvariants(s, 0, config.cacheMaxEntries(), config.cacheMaxBytes());
    }

    @Test
    void upstreamFailuresAreCountedByKind() {
        // The 429 goes last: it starts a cooldown, which would stop the later requests from calling the upstream.
        upstream.enqueue(response(500, "{}"))
                .enqueue(response(503, "{}"))
                .enqueue(uri -> {
                    throw new UpstreamException(UpstreamException.Kind.TIMEOUT, "slow");
                })
                .enqueue(uri -> {
                    throw new UpstreamException(UpstreamException.Kind.CONNECTION, "refused");
                })
                .enqueue(uri -> {
                    throw new UpstreamException(UpstreamException.Kind.TOO_LARGE, "big");
                })
                .enqueue(uri -> {
                    throw new IllegalStateException("injected bug");
                })
                .enqueue(response(429, "{}"));

        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(504, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        ProxyResponse bug = get("/v1/forecast", BERLIN);
        assertEquals(500, bug.status());
        assertEquals(Outcome.INTERNAL_ERROR, bug.outcome());
        assertEquals(503, get("/v1/forecast", BERLIN).status());

        MetricsSnapshot s = metrics.snapshot();

        assertEquals(expected(Outcome.UPSTREAM_ERROR, 6, Outcome.INTERNAL_ERROR, 1), s.requests().outcomes());
        assertEquals(7, s.upstream().calls());
        assertEquals(3, s.upstream().responses());
        assertEquals(1, s.upstream().status429());
        assertEquals(2, s.upstream().status5xx());
        assertEquals(new MetricsSnapshot.Failures(1, 1, 1), s.upstream().failures());
        assertEquals(6, s.derived().lookups(), "INTERNAL_ERROR is not a lookup");
        assertEquals(0, s.derived().upstreamCallsSaved());
        assertEquals(0.0, s.derived().cacheHitRatio());
        assertEquals(7, s.latencyMs().upstream().count(), "failures are timed too");
        assertInvariants(s, 1, config.cacheMaxEntries(), config.cacheMaxBytes());
    }

    /** Every way a request can need the upstream yet not call it, each counted exactly once. */
    @Test
    @Timeout(10)
    void skippedCallsAndUnavailableAreExact() throws Exception {
        configure(Map.of(
                "nimbus.upstream.perMinute", "2",
                "nimbus.upstream.maxConcurrent", "1",
                "nimbus.upstream.permitWaitMs", "0"));
        upstream.enqueue(response(429, "{}"));

        assertEquals(Outcome.UPSTREAM_ERROR, get("/v1/forecast", "latitude=1&longitude=1").outcome()); // Cooldown.
        assertEquals(Outcome.UNAVAILABLE, get("/v1/forecast", "latitude=2&longitude=2").outcome());   // cooldown
        clock.advance(config.upstreamCooldown());                                     // Also refills the minute.
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=2&longitude=2").outcome());
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=3&longitude=3").outcome());
        assertEquals(Outcome.UNAVAILABLE, get("/v1/forecast", "latitude=4&longitude=4").outcome());   // budget
        assertEquals(Outcome.HIT, get("/v1/forecast", "latitude=2&longitude=2").outcome());
        clock.advance(Duration.ofSeconds(30));                                         // One budget token.
        CountDownLatch gate = new CountDownLatch(1);
        upstream.gate(gate);
        Future<ProxyResponse> holder;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            holder = pool.submit(() -> get("/v1/forecast", "latitude=5&longitude=5"));
            upstream.awaitCallCount(4, Duration.ofSeconds(5));
            assertEquals(Outcome.UNAVAILABLE, get("/v1/forecast", "latitude=6&longitude=6").outcome()); // noPermit
            gate.countDown();
        }
        assertEquals(Outcome.MISS, holder.get().outcome());

        MetricsSnapshot s = metrics.snapshot();

        assertEquals(expected(Outcome.UPSTREAM_ERROR, 1, Outcome.UNAVAILABLE, 3, Outcome.MISS, 3, Outcome.HIT, 1),
                s.requests().outcomes());
        assertEquals(8, s.requests().total());
        assertEquals(new MetricsSnapshot.Skipped(1, 1, 1), s.upstream().skipped());
        assertEquals(4, s.upstream().calls());
        assertEquals(4, s.upstream().responses());
        assertEquals(1, s.upstream().status429());
        assertEquals(8, s.derived().lookups(), "UNAVAILABLE reached the lookup");
        assertEquals(1, s.derived().upstreamCallsSaved(), "rejections don't count as saved calls");
        assertEquals(1.0 / 8, s.derived().cacheHitRatio());
        assertFalse(s.upstream().cooldownActive());
        assertEquals(4, upstream.callCount());
        assertInvariants(s, 0, config.cacheMaxEntries(), config.cacheMaxBytes());
    }

    /**
     * STALE_WITHOUT_UPSTREAM vs STALE_AFTER_UPSTREAM_FAILURE are counted separately and exactly.
     * upstreamCallsSaved includes the first and excludes the second. staleTotal is their sum.
     */
    @Test
    @Timeout(10)
    void staleOutcomesCountedSeparatelyAndFollowInvariants() {
        configure(Map.of("nimbus.upstream.cooldownSeconds", "60"));
        upstream.respond(response(200, "{\"v\":1}"));

        // 1 & 2: Warm two keys
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=1&longitude=1").outcome());
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=2&longitude=2").outcome());

        // Advance past TTL (300 s) but within stale window (3600 s)
        clock.advance(Duration.ofSeconds(301));

        // 3: key1 encounters upstream 429 -> STALE_AFTER_UPSTREAM_FAILURE, starts 60s cooldown
        upstream.enqueue(response(429, "{}", "Retry-After", "60"));
        ProxyResponse r3 = get("/v1/forecast", "latitude=1&longitude=1");
        assertEquals(Outcome.STALE_AFTER_UPSTREAM_FAILURE, r3.outcome());
        assertEquals("STALE", r3.headers().get("X-Cache"));

        // 4: key2 while cooldown is active -> STALE_WITHOUT_UPSTREAM (no upstream call)
        ProxyResponse r4 = get("/v1/forecast", "latitude=2&longitude=2");
        assertEquals(Outcome.STALE_WITHOUT_UPSTREAM, r4.outcome());
        assertEquals("STALE", r4.headers().get("X-Cache"));

        // Advance past cooldown and warm a third key, then hit it
        clock.advance(Duration.ofSeconds(61));
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=3&longitude=3").outcome()); // 5
        assertEquals(Outcome.HIT, get("/v1/forecast", "latitude=3&longitude=3").outcome());  // 6

        MetricsSnapshot s = metrics.snapshot();

        assertEquals(6, s.requests().total());
        assertEquals(expected(
                Outcome.HIT, 1,
                Outcome.MISS, 3,
                Outcome.STALE_AFTER_UPSTREAM_FAILURE, 1,
                Outcome.STALE_WITHOUT_UPSTREAM, 1),
                s.requests().outcomes());
        assertEquals(6, s.derived().lookups());
        assertEquals(2, s.derived().staleTotal(), "staleTotal is sum of both stale outcomes");
        assertEquals(2, s.derived().upstreamCallsSaved(),
                "upstreamCallsSaved includes HIT and STALE_WITHOUT_UPSTREAM, excludes STALE_AFTER_UPSTREAM_FAILURE");
        assertEquals(1.0 / 6, s.derived().cacheHitRatio());
        assertEquals(2.0 / 6, s.derived().upstreamAvoidanceRatio());
        assertEquals(4, s.upstream().calls(), "2 warm misses + 1 failed call + 1 new miss");
        assertEquals(4, s.upstream().responses());
        assertEquals(1, s.upstream().status429());
        assertEquals(new MetricsSnapshot.Skipped(0, 1, 0), s.upstream().skipped());
        assertInvariants(s, 0, config.cacheMaxEntries(), config.cacheMaxBytes());
    }
}
