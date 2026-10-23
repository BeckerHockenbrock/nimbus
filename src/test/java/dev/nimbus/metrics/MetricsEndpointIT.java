package dev.nimbus.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.server.ProxyServer;
import dev.nimbus.upstream.HttpUpstreamClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** {@code GET /metrics} over real sockets, checked against the fake upstream's own request count (I4). */
@Timeout(30)
class MetricsEndpointIT {

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private FakeUpstreamServer fake;
    private HttpUpstreamClient upstreamClient;
    private ProxyServer server;
    private NimbusConfig config;

    @BeforeEach
    void setUp() throws IOException {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString()));
        upstreamClient = new HttpUpstreamClient(config.upstreamConnectTimeout(), config.upstreamDeadline(),
                config.upstreamMaxResponseBytes());
        LinkedHashMapCache<String, CacheEntry> cache = new LinkedHashMapCache<>(config.cacheMaxEntries(),
                config.cacheMaxBytes(), (key, entry) -> entry.weightBytes(key));
        ProxyMetrics metrics = new ProxyMetrics(config, cache, InstantSource.system());
        ProxyService service = new ProxyService(config.routes(), cache, upstreamClient, FreshnessPolicy.from(config),
                metrics, InstantSource.system(), config);
        server = new ProxyServer(config, service, metrics);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        upstreamClient.close();
        fake.close();
        http.close();
    }

    private HttpResponse<String> send(String pathAndQuery) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject metricsJson() throws IOException, InterruptedException {
        HttpResponse<String> response = send("/metrics");
        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * Server latency is recorded just after the response is written, so the last sample can land a moment after
     * the client has its response. Poll briefly (each poll is an HTTP round trip, not a sleep).
     */
    private MetricsSnapshot awaitLatencySamples(long expected) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        MetricsSnapshot snapshot;
        do {
            snapshot = new Gson().fromJson(send("/metrics").body(), MetricsSnapshot.class);
        } while (snapshot.latencyMs().all().totalRecorded() < expected && System.nanoTime() < deadline);
        return snapshot;
    }

    @Test
    void countsMatchAfterMixedRequests() throws Exception {
        String berlin = "/v1/forecast?latitude=52.52437&longitude=13.41053&current=temperature_2m";
        List<String> requests = List.of(
                berlin,                                   // MISS
                berlin,                                   // HIT
                "/v1/forecast?current=temperature_2m&longitude=13.41053&latitude=52.52437", // HIT (reordered)
                "/v1/search?name=Berlin",                 // MISS
                "/v1/search?name=Berlin",                 // HIT
                "/v1/forecast?longitude=1",               // INVALID (400)
                "/v1/unknown",                            // INVALID (404)
                "/");                                     // INVALID (404)
        for (String r : requests) {
            send(r);
        }
        fake.options(fake.options().withMode(FakeUpstreamServer.Mode.ERROR500));
        assertEquals(502, send("/v1/search?name=Paris").statusCode()); // UPSTREAM_ERROR
        send("/health");
        send("/metrics");

        MetricsSnapshot s = awaitLatencySamples(9);

        assertEquals(9, s.requests().total(), "/metrics and /health are not counted");
        assertEquals(3, s.outcome(Outcome.HIT));
        assertEquals(2, s.outcome(Outcome.MISS));
        assertEquals(3, s.outcome(Outcome.INVALID));
        assertEquals(1, s.outcome(Outcome.UPSTREAM_ERROR));
        assertEquals(6, s.derived().lookups());
        assertEquals(3, s.derived().upstreamCallsSaved());
        assertEquals(0.5, s.derived().cacheHitRatio());
        assertEquals(3, s.upstream().calls());
        assertEquals(1, s.upstream().status5xx());
        assertEquals(fake.totalRequests(), s.upstream().calls(), "I4: proxy calls == requests the fake received");
        assertEquals(2, s.cache().entries());
        assertEquals(9, s.latencyMs().all().totalRecorded());
        assertEquals(3, s.latencyMs().hit().totalRecorded());
        assertEquals(2, s.latencyMs().miss().totalRecorded());
        assertEquals(0, s.latencyMs().stale().totalRecorded());
        assertEquals(3, s.latencyMs().upstream().totalRecorded());
        assertTrue(s.latencyMs().hit().p50() <= s.latencyMs().miss().max(), "hits are not slower than every miss");
        MetricsAccuracyTest.assertInvariants(s, 0, config.cacheMaxEntries(), config.cacheMaxBytes());
    }

    @Test
    void jsonHasTheDocumentedShape() throws Exception {
        JsonObject json = metricsJson();

        for (String key : List.of("uptimeSeconds", "requests", "derived", "upstream", "cache", "latencyMs")) {
            assertTrue(json.has(key), key);
        }
        JsonObject outcomes = json.getAsJsonObject("requests").getAsJsonObject("outcomes");
        for (Outcome outcome : Outcome.values()) {
            assertEquals(0, outcomes.get(outcome.name()).getAsLong(), outcome.name());
        }
        JsonObject derived = json.getAsJsonObject("derived");
        assertTrue(derived.get("cacheHitRatio").isJsonNull(), "null ratios are written out, not omitted");
        assertTrue(derived.get("upstreamAvoidanceRatio").isJsonNull());
        JsonObject upstream = json.getAsJsonObject("upstream");
        for (String key : List.of("calls", "responses", "status429", "status5xx", "failures", "skipped",
                "cooldownActive", "inFlight")) {
            assertTrue(upstream.has(key), key);
        }
        assertEquals(List.of("timeout", "connection", "tooLarge"),
                List.copyOf(upstream.getAsJsonObject("failures").keySet()));
        assertEquals(List.of("budget", "cooldown", "noPermit"),
                List.copyOf(upstream.getAsJsonObject("skipped").keySet()));
        assertEquals(List.of("entries", "maxEntries", "bytes", "maxBytes", "evictions", "impl"),
                List.copyOf(json.getAsJsonObject("cache").keySet()));
        JsonObject latency = json.getAsJsonObject("latencyMs");
        assertEquals(List.of("all", "hit", "miss", "stale", "upstream"), List.copyOf(latency.keySet()));
        JsonObject all = latency.getAsJsonObject("all");
        assertEquals(List.of("count", "totalRecorded", "p50", "p95", "p99", "max"), List.copyOf(all.keySet()));
        assertTrue(all.get("p50").isJsonNull());

        // The GUI deserializes the same records.
        MetricsSnapshot parsed = new Gson().fromJson(json, MetricsSnapshot.class);
        assertEquals(0L, parsed.outcome(Outcome.HIT));
        assertEquals("linkedhashmap", parsed.cache().impl());
    }

    @Test
    void metricsEndpointRejectsOtherMethodsAndPaths() throws Exception {
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
        assertEquals(404, send("/metrics/extra").statusCode());
        assertEquals(0, new Gson().fromJson(send("/metrics").body(), MetricsSnapshot.class).requests().total());
    }
}
