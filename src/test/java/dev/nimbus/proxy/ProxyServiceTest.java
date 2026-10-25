package dev.nimbus.proxy;

import static dev.nimbus.testutil.TestEntries.response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nimbus.cache.Cache;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.MetricsSnapshot;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.MutableClock;
import dev.nimbus.testutil.ProxyFixture;
import dev.nimbus.upstream.UpstreamException;
import dev.nimbus.upstream.UpstreamResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProxyServiceTest {

    private static final String CLIENT = "127.0.0.1";

    private static final Map<String, String> UPSTREAMS = Map.of(
            "nimbus.forecast.upstream", "http://upstream.test/v1/forecast",
            "nimbus.geocoding.upstream", "http://geo.test/v1/search");

    private final NimbusConfig config = NimbusConfig.withOverrides(UPSTREAMS);
    private final FakeUpstreamClient upstream = new FakeUpstreamClient();
    private final MutableClock clock = new MutableClock();
    private final ProxyFixture fixture = ProxyFixture.create(config, upstream, clock);
    private final Cache<String, CacheEntry> cache = fixture.cache();
    private final ProxyService service = fixture.service();

    private ProxyResponse get(String path, String rawQuery) {
        return get(fixture, CLIENT, path, rawQuery);
    }

    private static ProxyResponse get(ProxyFixture fixture, String client, String path, String rawQuery) {
        return fixture.service().handle(new ProxyRequest("GET", path, rawQuery, client));
    }

    /** A separate service over the same fake upstream and clock, with {@code overrides} on top of the defaults. */
    private ProxyFixture fixtureWith(Map<String, String> overrides) {
        Map<String, String> all = new HashMap<>(UPSTREAMS);
        all.putAll(overrides);
        return ProxyFixture.create(NimbusConfig.withOverrides(all), upstream, clock);
    }

    private static JsonObject json(ProxyResponse response) {
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void assertProxyError(ProxyResponse response, int status, Outcome outcome) {
        assertEquals(status, response.status());
        assertEquals(outcome, response.outcome());
        assertEquals(ProxyResponse.JSON_CONTENT_TYPE, response.headers().get("Content-Type"));
        assertNull(response.headers().get("X-Cache"), "errors have no X-Cache");
        JsonObject body = json(response);
        assertTrue(body.get("error").getAsBoolean());
        assertEquals("proxy", body.get("source").getAsString());
        assertFalse(body.get("reason").getAsString().isBlank());
    }

    @Test
    void upstreamReceivesTheCanonicalUrl() {
        get("/v1/forecast", "longitude=13.41053&current=temperature_2m&latitude=52.52437&current=is_day");

        assertEquals(URI.create("http://upstream.test/v1/forecast"
                        + "?current=temperature_2m&current=is_day&latitude=52.52437&longitude=13.41053"),
                upstream.lastCall());

        get("/v1/search", "name=S%C3%A3o+Paulo&count=1");
        assertEquals(URI.create("http://geo.test/v1/search?count=1&name=S%C3%A3o+Paulo"), upstream.lastCall());
    }

    @Test
    void relaysStatusBodyAndHeaders() {
        byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
        upstream.respond(response(200, body,
                "Content-Type", "application/json; charset=utf-8",
                "Cache-Control", "public",
                "Cache-Control", "max-age=86400",
                "Set-Cookie", "not=relayed"));

        ProxyResponse response = get("/v1/search", "name=Berlin");

        assertEquals(200, response.status());
        assertEquals(Outcome.MISS, response.outcome());
        assertArrayEquals(body, response.body());
        assertEquals("application/json; charset=utf-8", response.headers().get("Content-Type"));
        assertEquals("public, max-age=86400", response.headers().get("Cache-Control"));
        assertEquals("MISS", response.headers().get("X-Cache"));
        assertEquals("1.000", response.headers().get("X-Upstream-Latency-Ms"));
        assertNull(response.headers().get("Set-Cookie"));
    }

    @Test
    void noCacheControlFromUpstreamMeansNoneRelayed() {
        upstream.respond(response(200, "{}", "Content-Type", "application/json"));

        assertNull(get("/v1/forecast", "latitude=1&longitude=2").headers().get("Cache-Control"));
    }

    @ParameterizedTest
    @CsvSource({"400", "404", "301"})
    void upstreamClientErrorsAreRelayedAsMiss(int status) {
        upstream.respond(response(status, "{\"reason\":\"Latitude must be in range\",\"error\":true}",
                "Content-Type", "application/json; charset=utf-8"));

        ProxyResponse response = get("/v1/forecast", "latitude=999&longitude=2");

        assertEquals(status, response.status());
        assertEquals(Outcome.MISS, response.outcome());
        assertEquals("Latitude must be in range", json(response).get("reason").getAsString());
    }

    @Test
    void unknownPathIs404WithoutUpstreamCall() {
        assertProxyError(get("/v1/archive", "latitude=1&longitude=2"), 404, Outcome.INVALID);
        assertProxyError(get("/", null), 404, Outcome.INVALID);
        assertProxyError(get("/v1/forecast/", "latitude=1&longitude=2"), 404, Outcome.INVALID);
        assertEquals(0, upstream.callCount());
    }

    @Test
    void nonGetIs405WithoutUpstreamCall() {
        ProxyResponse response = service.handle(new ProxyRequest("POST", "/v1/forecast", "latitude=1&longitude=2",
                CLIENT));

        assertProxyError(response, 405, Outcome.INVALID);
        assertEquals("GET", response.headers().get("Allow"));
        assertEquals(0, upstream.callCount());
    }

    @Test
    void badQueryIs400Or414WithoutUpstreamCall() {
        assertProxyError(get("/v1/forecast", "latitude=1"), 400, Outcome.INVALID);
        assertProxyError(get("/v1/search", "name=%zz"), 400, Outcome.INVALID);
        assertProxyError(get("/v1/search", "name=" + "a".repeat(config.maxQueryLength())), 414, Outcome.INVALID);
        assertEquals(0, upstream.callCount());
    }

    @Test
    void timeoutIs504() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.TIMEOUT, "slow"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 504, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void tooLargeIs502() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.TOO_LARGE, "big"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void connectionFailureIs502() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.CONNECTION, "refused"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503"})
    void upstream5xxIs502(int status) {
        upstream.respond(response(status, "{\"error\":true}"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void upstream429Is503WithRetryAfter() {
        upstream.respond(response(429, "{\"reason\":\"Minutely API request limit exceeded\",\"error\":true}"));

        ProxyResponse response = get("/v1/forecast", "latitude=1&longitude=2");

        assertProxyError(response, 503, Outcome.UPSTREAM_ERROR);
        assertEquals(String.valueOf(config.upstreamCooldown().toSeconds()), response.headers().get("Retry-After"));
    }

    @Test
    void longerUpstreamRetryAfterWins() {
        upstream.respond(response(429, "{}", "Retry-After", "120"));

        assertEquals("120", get("/v1/forecast", "latitude=1&longitude=2").headers().get("Retry-After"));
    }

    // --- Phase 2: caching ---

    private static final String BERLIN = "latitude=52.52437&longitude=13.41053&current=temperature_2m";
    private static final String BERLIN_KEY = "/v1/forecast?current=temperature_2m&latitude=52.52437&longitude=13.41053";

    private static UpstreamResponse forecastBody(String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Content-Type";
        all[1] = "application/json; charset=utf-8";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return response(200, body, all);
    }

    @Test
    void missThenHitWithOneUpstreamCall() {
        upstream.respond(forecastBody("{\"t\":1}"));

        ProxyResponse first = get("/v1/forecast", BERLIN);
        ProxyResponse second = get("/v1/forecast", BERLIN);

        assertEquals(Outcome.MISS, first.outcome());
        assertEquals("MISS", first.headers().get("X-Cache"));
        assertEquals(Outcome.HIT, second.outcome());
        assertEquals(200, second.status());
        assertEquals("HIT", second.headers().get("X-Cache"));
        assertEquals("application/json; charset=utf-8", second.headers().get("Content-Type"));
        assertNull(second.headers().get("X-Upstream-Latency-Ms"));
        assertEquals(1, upstream.callCount());
        assertEquals(1, cache.size());
        assertTrue(cache.keysInLruOrder().contains(BERLIN_KEY));
    }

    @Test
    void differentlyOrderedNamesShareAnEntry() {
        get("/v1/forecast", "latitude=52.52437&longitude=13.41053&current=temperature_2m");

        ProxyResponse reordered = get("/v1/forecast", "current=temperature_2m&longitude=13.41053&latitude=52.52437");

        assertEquals(Outcome.HIT, reordered.outcome());
        assertEquals(1, upstream.callCount());
    }

    @Test
    void swappedValueOrderIsASeparateEntry() {
        get("/v1/forecast", "latitude=1&longitude=2&current=temperature_2m,wind_speed_10m");

        ProxyResponse swapped = get("/v1/forecast", "latitude=1&longitude=2&current=wind_speed_10m,temperature_2m");

        assertEquals(Outcome.MISS, swapped.outcome());
        assertEquals(2, upstream.callCount());
        assertEquals(2, cache.size());
    }

    @Test
    void expiresAfterTheConfiguredTtl() {
        get("/v1/forecast", BERLIN);
        clock.advance(Duration.ofSeconds(299));
        assertEquals(Outcome.HIT, get("/v1/forecast", BERLIN).outcome());

        clock.advance(Duration.ofSeconds(1)); // Exactly freshUntil: never a HIT.
        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());
        assertEquals(2, upstream.callCount());

        clock.advance(Duration.ofSeconds(301));
        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());
        assertEquals(3, upstream.callCount());
    }

    @Test
    void noStoreIsNeverHitAndRemovesTheExistingEntry() {
        upstream.enqueue(forecastBody("{\"v\":\"old\"}"));
        upstream.respond(forecastBody("{\"v\":\"new\"}", "Cache-Control", "no-store"));
        get("/v1/forecast", BERLIN);
        assertEquals(1, cache.size());
        clock.advance(Duration.ofSeconds(301)); // Expired but inside the stale window, so still cached.
        assertEquals(1, cache.size());

        ProxyResponse refreshed = get("/v1/forecast", BERLIN);
        ProxyResponse again = get("/v1/forecast", BERLIN);

        assertEquals("{\"v\":\"new\"}", new String(refreshed.body(), StandardCharsets.UTF_8));
        assertEquals("no-store", refreshed.headers().get("Cache-Control"));
        assertEquals(Outcome.MISS, again.outcome());
        assertNull(cache.get(BERLIN_KEY), "store-or-remove: the old body is gone");
        assertEquals(3, upstream.callCount());
    }

    @Test
    void tooBigForAnEntryIsRelayedButNotCached() {
        int max = (int) config.cacheMaxEntryBytes();
        upstream.respond(response(200, new byte[max + 1]));

        assertEquals(max + 1, get("/v1/forecast", BERLIN).body().length);
        assertEquals(Outcome.MISS, get("/v1/forecast", BERLIN).outcome());
        assertEquals(0, cache.size());
    }

    @Test
    void upstream400IsNeverCached() {
        upstream.respond(response(400, "{\"reason\":\"bad\",\"error\":true}"));

        get("/v1/forecast", BERLIN);
        ProxyResponse second = get("/v1/forecast", BERLIN);

        assertEquals(400, second.status());
        assertEquals(Outcome.MISS, second.outcome());
        assertEquals(2, upstream.callCount());
        assertEquals(0, cache.size());
    }

    @Test
    void nonOkResponsesLeaveTheExistingEntryAlone() {
        upstream.enqueue(forecastBody("{\"v\":1}"));
        upstream.enqueue(response(404, "{}"));
        upstream.respond(response(503, "{}"));
        get("/v1/forecast", BERLIN);
        clock.advance(Duration.ofSeconds(301));

        assertEquals(404, get("/v1/forecast", BERLIN).status());
        assertEquals(502, get("/v1/forecast", BERLIN).status(), "stale-if-error arrives in Phase 5");

        assertEquals(1, cache.size(), "kept for stale-if-error");
    }

    @Test
    void entryPastItsStaleWindowIsRemovedAtLookup() {
        get("/v1/forecast", BERLIN);
        upstream.respond(response(500, "{}"));
        clock.advance(config.forecastTtl().plus(config.forecastStale()));

        assertEquals(502, get("/v1/forecast", BERLIN).status());

        assertEquals(0, cache.size());
    }

    @Test
    void servedBytesAreTheUpstreamBytes() {
        byte[] body = "{\"temperature_2m\":12.3,\"unit\":\"°C\"}".getBytes(StandardCharsets.UTF_8);
        upstream.respond(response(200, body, "Content-Type", "application/json"));

        ProxyResponse miss = get("/v1/forecast", BERLIN);
        ProxyResponse hit = get("/v1/forecast", BERLIN);

        assertArrayEquals(body, miss.body());
        assertArrayEquals(body, hit.body());
        assertSame(body, hit.body(), "not copied");
    }

    @Test
    void ageAndMaxAgeAtSeveralOffsets() {
        get("/v1/forecast", BERLIN);
        assertAgeAndMaxAge(0, 300);
        clock.advance(Duration.ofMillis(100_500));
        assertAgeAndMaxAge(100, 199);
        clock.advance(Duration.ofMillis(199_400)); // 299.9 s
        assertAgeAndMaxAge(299, 0);
    }

    @Test
    void upstreamAgeIsAddedToAge() {
        upstream.respond(forecastBody("{}", "Age", "20"));
        get("/v1/forecast", BERLIN);
        clock.advance(Duration.ofSeconds(10));

        assertAgeAndMaxAge(30, 270);
    }

    @Test
    void geocodingUsesItsOwnLifetime() {
        upstream.respond(response(200, "{\"results\":[]}", "Cache-Control", "public, max-age=86400"));
        get("/v1/search", "name=Berlin");
        clock.advance(Duration.ofHours(23));

        ProxyResponse hit = get("/v1/search", "name=Berlin");

        assertEquals(Outcome.HIT, hit.outcome());
        assertEquals("max-age=3600", hit.headers().get("Cache-Control"));
        assertEquals(String.valueOf(Duration.ofHours(23).toSeconds()), hit.headers().get("Age"));
    }

    private void assertAgeAndMaxAge(long age, long maxAge) {
        ProxyResponse hit = get("/v1/forecast", BERLIN);
        assertEquals(Outcome.HIT, hit.outcome());
        assertEquals(String.valueOf(age), hit.headers().get("Age"));
        assertEquals("max-age=" + maxAge, hit.headers().get("Cache-Control"));
    }

    // --- Phase 4: rate limiting and upstream protection ---

    private static MetricsSnapshot.Skipped skipped(ProxyFixture f) {
        return f.metrics().snapshot().upstream().skipped();
    }

    @Test
    void clientOverItsLimitGets429WithoutAnUpstreamCall() {
        ProxyFixture f = fixtureWith(Map.of(
                "nimbus.ratelimit.client.capacity", "3",
                "nimbus.ratelimit.client.refillPerSecond", "0.5"));

        ProxyResponse miss = get(f, CLIENT, "/v1/forecast", BERLIN);
        ProxyResponse invalid = get(f, CLIENT, "/v1/forecast", "latitude=1");
        ProxyResponse hit = get(f, CLIENT, "/v1/forecast", BERLIN);
        ProxyResponse limited = get(f, CLIENT, "/v1/forecast", BERLIN);

        assertEquals(Outcome.MISS, miss.outcome());
        assertEquals("3", miss.headers().get("X-RateLimit-Limit"));
        assertEquals("2", miss.headers().get("X-RateLimit-Remaining"));
        assertEquals(400, invalid.status(), "a bad query is checked after the rate limit, and spends a token");
        assertEquals("1", invalid.headers().get("X-RateLimit-Remaining"));
        assertEquals(Outcome.HIT, hit.outcome(), "hits are rate-limited too");
        assertEquals("0", hit.headers().get("X-RateLimit-Remaining"));
        assertProxyError(limited, 429, Outcome.RATE_LIMITED);
        assertEquals("2", limited.headers().get("Retry-After"), "one token at 0.5/s");
        assertEquals("3", limited.headers().get("X-RateLimit-Limit"));
        assertEquals("0", limited.headers().get("X-RateLimit-Remaining"));
        assertEquals(1, upstream.callCount());

        assertEquals(Outcome.INVALID, get(f, CLIENT, "/v1/nope", BERLIN).outcome(), "404 comes before the limit");
        assertEquals(Outcome.HIT, get(f, "192.0.2.7", "/v1/forecast", BERLIN).outcome(), "clients are independent");
        clock.advance(Duration.ofSeconds(2));
        assertEquals(Outcome.HIT, get(f, CLIENT, "/v1/forecast", BERLIN).outcome());
        assertEquals(1, upstream.callCount());
    }

    @Test
    void emptyBudgetIs503UnavailableWithoutAnUpstreamCall() {
        // One permit, so a permit leaked by the budget check would also show up as a noPermit skip.
        ProxyFixture f = fixtureWith(Map.of(
                "nimbus.upstream.perMinute", "2",
                "nimbus.upstream.maxConcurrent", "1",
                "nimbus.upstream.permitWaitMs", "0"));

        assertEquals(Outcome.MISS, get(f, CLIENT, "/v1/forecast", "latitude=1&longitude=1").outcome());
        assertEquals(Outcome.MISS, get(f, CLIENT, "/v1/forecast", "latitude=2&longitude=2").outcome());
        ProxyResponse unavailable = get(f, CLIENT, "/v1/forecast", "latitude=3&longitude=3");

        assertProxyError(unavailable, 503, Outcome.UNAVAILABLE);
        assertEquals("30", unavailable.headers().get("Retry-After"), "2 per minute refills one token every 30 s");
        assertEquals(new MetricsSnapshot.Skipped(1, 0, 0), skipped(f));
        assertEquals(2, upstream.callCount());
        assertEquals(Outcome.HIT, get(f, CLIENT, "/v1/forecast", "latitude=1&longitude=1").outcome(),
                "hits don't need the budget");

        clock.advance(Duration.ofSeconds(30));
        assertEquals(Outcome.MISS, get(f, CLIENT, "/v1/forecast", "latitude=3&longitude=3").outcome());
        assertEquals(new MetricsSnapshot.Skipped(1, 0, 0), skipped(f));
    }

    @Test
    void upstream429StartsACooldownThatSkipsTheNextMiss() {
        upstream.enqueue(response(429, "{}", "Retry-After", "90"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=1"), 503, Outcome.UPSTREAM_ERROR);
        assertTrue(fixture.metrics().snapshot().upstream().cooldownActive());
        ProxyResponse skippedMiss = get("/v1/forecast", "latitude=2&longitude=2");

        assertProxyError(skippedMiss, 503, Outcome.UNAVAILABLE);
        assertEquals("90", skippedMiss.headers().get("Retry-After"));
        assertEquals(new MetricsSnapshot.Skipped(0, 1, 0), skipped(fixture));
        assertEquals(1, upstream.callCount());

        clock.advance(Duration.ofMillis(89_500));
        assertEquals("1", get("/v1/forecast", "latitude=2&longitude=2").headers().get("Retry-After"), "rounded up");
        clock.advance(Duration.ofMillis(500));
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=2&longitude=2").outcome());
        assertFalse(fixture.metrics().snapshot().upstream().cooldownActive());
        assertEquals(new MetricsSnapshot.Skipped(0, 2, 0), skipped(fixture));
        assertEquals(2, upstream.callCount());
    }

    @Test
    void shorterUpstreamRetryAfterKeepsTheConfiguredCooldown() {
        upstream.enqueue(response(429, "{}", "Retry-After", "5"));
        get("/v1/forecast", "latitude=1&longitude=1");

        clock.advance(config.upstreamCooldown().minusSeconds(1));
        assertEquals(Outcome.UNAVAILABLE, get("/v1/forecast", "latitude=1&longitude=1").outcome());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(Outcome.MISS, get("/v1/forecast", "latitude=1&longitude=1").outcome());
    }

    @Test
    @Timeout(10)
    void concurrencyCapTurnsAwayAThirdMissAfterThePermitWait() throws Exception {
        ProxyFixture f = fixtureWith(Map.of(
                "nimbus.upstream.maxConcurrent", "2",
                "nimbus.upstream.permitWaitMs", "50"));
        CountDownLatch gate = new CountDownLatch(1);
        upstream.gate(gate);
        ProxyResponse third;
        long waitedNanos;
        Future<ProxyResponse> first;
        Future<ProxyResponse> second;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            first = pool.submit(() -> get(f, CLIENT, "/v1/forecast", "latitude=1&longitude=1"));
            second = pool.submit(() -> get(f, CLIENT, "/v1/forecast", "latitude=2&longitude=2"));
            upstream.awaitCallCount(2, Duration.ofSeconds(5));

            long start = System.nanoTime();
            third = get(f, CLIENT, "/v1/forecast", "latitude=3&longitude=3");
            waitedNanos = System.nanoTime() - start;
            assertEquals(2, f.metrics().snapshot().upstream().inFlight());
            gate.countDown();
        }

        assertProxyError(third, 503, Outcome.UNAVAILABLE);
        assertEquals("1", third.headers().get("Retry-After"));
        assertTrue(waitedNanos >= Duration.ofMillis(50).toNanos(), "waited permitWaitMs first");
        assertEquals(Outcome.MISS, first.get().outcome());
        assertEquals(Outcome.MISS, second.get().outcome());
        assertEquals(2, upstream.callCount());
        assertEquals(2, upstream.peakConcurrentCalls());
        assertEquals(new MetricsSnapshot.Skipped(0, 0, 1), skipped(f));
        assertEquals(0, f.metrics().snapshot().upstream().inFlight());
        assertEquals(Outcome.MISS, get(f, CLIENT, "/v1/forecast", "latitude=3&longitude=3").outcome(),
                "permits are released");
    }
}
