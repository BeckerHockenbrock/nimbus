package dev.nimbus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.testutil.ProxyFixture;
import dev.nimbus.upstream.HttpUpstreamClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.InstantSource;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end resilience tests over real sockets (PLAN.md, Phase 5):
 * <ul>
 *   <li>Fake in hang mode with warm cache &rarr; STALE after about 5 s.</li>
 *   <li>Fake in hang mode with cold cache &rarr; 504.</li>
 *   <li>Fake stopped &rarr; STALE / 502.</li>
 *   <li>Trickle mode &rarr; STALE / 504 (proves deadline covers body over real sockets).</li>
 * </ul>
 */
@Timeout(120)
class ResilienceIT {

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private FakeUpstreamServer fake;
    private HttpUpstreamClient upstreamClient;
    private ProxyServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (upstreamClient != null) {
            upstreamClient.close();
        }
        if (fake != null) {
            fake.close();
        }
        http.close();
    }

    private void startProxy(NimbusConfig config) throws IOException {
        upstreamClient = new HttpUpstreamClient(config.upstreamConnectTimeout(), config.upstreamDeadline(),
                config.upstreamMaxResponseBytes());
        ProxyFixture fixture = ProxyFixture.create(config, upstreamClient, InstantSource.system());
        server = new ProxyServer(config, fixture.service(), fixture.metrics(), fixture.rateLimiter());
        server.start();
    }

    private HttpResponse<String> send(String pathAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Fake in hang mode with a warm cache returns STALE after about 5 s. */
    @Test
    void hangModeWithWarmCacheReturnsStaleAfterDeadline() throws Exception {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString(),
                "nimbus.cache.forecast.ttlSeconds", "1",
                "nimbus.cache.forecast.staleSeconds", "3600",
                "nimbus.upstream.deadlineMs", "5000"));
        startProxy(config);

        // Warm the cache
        HttpResponse<String> initial = send("/v1/forecast?latitude=52.52&longitude=13.41");
        assertEquals(200, initial.statusCode());
        assertEquals("MISS", initial.headers().firstValue("X-Cache").orElseThrow());

        // Wait for TTL to expire (1 second TTL)
        Thread.sleep(1200);

        // Put fake in hang mode
        fake.options(fake.options().withMode(FakeUpstreamServer.Mode.HANG));

        long start = System.currentTimeMillis();
        HttpResponse<String> staleResponse = send("/v1/forecast?latitude=52.52&longitude=13.41");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(200, staleResponse.statusCode());
        assertEquals("STALE", staleResponse.headers().firstValue("X-Cache").orElseThrow());
        assertEquals("max-age=0", staleResponse.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(elapsed >= 4500, "Should take about 5 seconds due to upstream deadline: " + elapsed + " ms");
    }

    /** Fake in hang mode with a cold cache returns 504. */
    @Test
    void hangModeWithColdCacheReturns504() throws Exception {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults().withMode(FakeUpstreamServer.Mode.HANG));
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString(),
                "nimbus.upstream.deadlineMs", "2000"));
        startProxy(config);

        HttpResponse<String> response = send("/v1/forecast?latitude=10.0&longitude=10.0");
        assertEquals(504, response.statusCode());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("proxy", body.get("source").getAsString());
    }

    /** Fake stopped returns STALE for warm cache and 502 for cold cache. */
    @Test
    void fakeStoppedReturnsStaleAndColdReturns502() throws Exception {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString(),
                "nimbus.cache.forecast.ttlSeconds", "1",
                "nimbus.cache.forecast.staleSeconds", "3600"));
        startProxy(config);

        // Warm the cache for key A
        HttpResponse<String> warm = send("/v1/forecast?latitude=52.52&longitude=13.41");
        assertEquals(200, warm.statusCode());
        assertEquals("MISS", warm.headers().firstValue("X-Cache").orElseThrow());

        // Wait for TTL to expire
        Thread.sleep(1200);

        // Stop the upstream server
        fake.close();

        // Warm key returns STALE (200)
        HttpResponse<String> staleResponse = send("/v1/forecast?latitude=52.52&longitude=13.41");
        assertEquals(200, staleResponse.statusCode());
        assertEquals("STALE", staleResponse.headers().firstValue("X-Cache").orElseThrow());

        // Cold key returns 502
        HttpResponse<String> coldResponse = send("/v1/forecast?latitude=99.0&longitude=99.0");
        assertEquals(502, coldResponse.statusCode());
        JsonObject body = JsonParser.parseString(coldResponse.body()).getAsJsonObject();
        assertEquals("proxy", body.get("source").getAsString());
    }

    /** Trickle mode returns STALE for warm cache and 504 for cold cache (proves deadline covers body). */
    @Test
    void trickleModeReturnsStaleAndColdReturns504() throws Exception {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString(),
                "nimbus.cache.forecast.ttlSeconds", "1",
                "nimbus.cache.forecast.staleSeconds", "3600",
                "nimbus.upstream.deadlineMs", "1500"));
        startProxy(config);

        // Warm the cache for key A
        HttpResponse<String> warm = send("/v1/forecast?latitude=52.52&longitude=13.41");
        assertEquals(200, warm.statusCode());
        assertEquals("MISS", warm.headers().firstValue("X-Cache").orElseThrow());

        // Wait for TTL to expire
        Thread.sleep(1200);

        // Set fake upstream to TRICKLE mode
        fake.options(fake.options().withMode(FakeUpstreamServer.Mode.TRICKLE));

        // Warm key hits deadline during body trickle, returns STALE
        HttpResponse<String> staleResponse = send("/v1/forecast?latitude=52.52&longitude=13.41");
        assertEquals(200, staleResponse.statusCode());
        assertEquals("STALE", staleResponse.headers().firstValue("X-Cache").orElseThrow());

        // Cold key hits deadline during body trickle, returns 504
        HttpResponse<String> coldResponse = send("/v1/forecast?latitude=30.0&longitude=30.0");
        assertEquals(504, coldResponse.statusCode());
    }
}
