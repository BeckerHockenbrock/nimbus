package dev.nimbus.metrics;

import static dev.nimbus.testutil.TestEntries.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.cache.Cache;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.proxy.ProxyRequest;
import dev.nimbus.proxy.ProxyResponse;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.MutableClock;
import dev.nimbus.upstream.UpstreamException;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Exact metric counts for scripted request sequences (PLAN.md, Section 3.6). */
class MetricsAccuracyTest {

    private static final String BERLIN = "latitude=52.52437&longitude=13.41053&current=temperature_2m";

    private final NimbusConfig config = NimbusConfig.defaults();
    private final MutableClock clock = new MutableClock();
    private final FakeUpstreamClient upstream = new FakeUpstreamClient();
    private final Cache<String, CacheEntry> cache = new LinkedHashMapCache<>(config.cacheMaxEntries(),
            config.cacheMaxBytes(), (key, entry) -> entry.weightBytes(key));
    private final ProxyMetrics metrics = new ProxyMetrics(config, cache, clock);
    private final ProxyService service = new ProxyService(config.routes(), cache, upstream,
            FreshnessPolicy.from(config), metrics, clock, config);

    private ProxyResponse get(String path, String query) {
        return service.handle(new ProxyRequest("GET", path, query, "127.0.0.1"));
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

    /**
     * The plan's scripted sequence. Its step 4 (a rate-limited request) needs Phase 4's rate limiter and is added
     * there; the other seven steps are here, in order.
     */
    @Test
    void scriptedSequenceHasExactCounts() {
        upstream.respond(response(200, "{\"ok\":true}", "Content-Type", "application/json"));

        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());            // 1
        assertEquals(Outcome.HIT, get("/v1/forecast", BERLIN).outcome());             // 2
        assertEquals(Outcome.HIT, get("/v1/forecast", BERLIN).outcome());             // 3
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

        assertEquals(expected(Outcome.HIT, 2, Outcome.MISS, 3, Outcome.INVALID, 2), s.requests().outcomes());
        assertEquals(7, s.requests().total());
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
        assertEquals(3, s.latencyMs().upstream().count(), "one upstream sample per call");
        assertEquals(0, s.latencyMs().all().count(), "server latency is recorded by ProxyHandler, not here");
        assertEquals(1, s.cache().entries());
        assertEquals(config.cacheMaxEntries(), s.cache().maxEntries());
        assertEquals(config.cacheMaxBytes(), s.cache().maxBytes());
        assertEquals("linkedhashmap", s.cache().impl());
        assertEquals(config.forecastTtl().plusSeconds(42).toSeconds(), s.uptimeSeconds());
        assertInvariants(s, 0, config.cacheMaxEntries(), config.cacheMaxBytes());
    }

    @Test
    void upstreamFailuresAreCountedByKind() {
        upstream.enqueue(response(429, "{}"))
                .enqueue(response(500, "{}"))
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
                });

        assertEquals(503, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(504, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status());
        ProxyResponse bug = get("/v1/forecast", BERLIN);
        assertEquals(500, bug.status());
        assertEquals(Outcome.INTERNAL_ERROR, bug.outcome());

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
}
