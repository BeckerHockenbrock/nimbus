package dev.nimbus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.ProxyMetrics;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.upstream.HttpUpstreamClient;
import dev.nimbus.upstream.UpstreamClient;

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

/** End-to-end over real sockets: HTTP client → ProxyServer → fake upstream. */
@Timeout(30)
class ProxyServerIT {

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

    private NimbusConfig startFakeAndConfig() throws IOException {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        return NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString()));
    }

    private void startProxy(NimbusConfig config, UpstreamClient upstream) throws IOException {
        LinkedHashMapCache<String, CacheEntry> cache = new LinkedHashMapCache<>(
                config.cacheMaxEntries(), config.cacheMaxBytes(), (key, entry) -> entry.weightBytes(key));
        ProxyMetrics metrics = new ProxyMetrics(config, cache, InstantSource.system());
        server = new ProxyServer(config, new ProxyService(config.routes(), cache, upstream,
                FreshnessPolicy.from(config), metrics, InstantSource.system(), config), metrics);
        server.start();
    }

    private HttpResponse<String> send(String method, String pathAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + pathAndQuery))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Test
    void relaysEndToEnd() throws Exception {
        NimbusConfig config = startFakeAndConfig();
        upstreamClient = new HttpUpstreamClient(config.upstreamConnectTimeout(), config.upstreamDeadline(),
                config.upstreamMaxResponseBytes());
        startProxy(config, upstreamClient);

        HttpResponse<String> forecast = send("GET", "/v1/forecast?longitude=13.41053&latitude=52.52437");
        assertEquals(200, forecast.statusCode());
        assertEquals("MISS", forecast.headers().firstValue("X-Cache").orElseThrow());
        assertEquals("application/json; charset=utf-8", forecast.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(forecast.headers().firstValue("X-Upstream-Latency-Ms").isPresent());
        JsonObject body = json(forecast);
        assertEquals(52.52437, body.get("latitude").getAsDouble());
        assertEquals("latitude=52.52437&longitude=13.41053",
                body.getAsJsonObject("fake").get("query").getAsString(), "upstream got the canonical query");
        assertEquals(1, fake.requestsFor("/v1/forecast?latitude=52.52437&longitude=13.41053"));

        HttpResponse<String> hit = send("GET", "/v1/forecast?latitude=52.52437&longitude=13.41053");
        assertEquals(200, hit.statusCode());
        assertEquals("HIT", hit.headers().firstValue("X-Cache").orElseThrow());
        assertEquals("0", hit.headers().firstValue("Age").orElseThrow());
        assertEquals(forecast.body(), hit.body(), "byte-identical over HTTP");
        assertEquals(1, fake.requestsFor("/v1/forecast?latitude=52.52437&longitude=13.41053"));

        HttpResponse<String> search = send("GET", "/v1/search?name=Berlin&count=1");
        assertEquals(200, search.statusCode());
        assertEquals("Berlin", json(search).getAsJsonArray("results").get(0).getAsJsonObject()
                .get("name").getAsString());

        HttpResponse<String> flagForm = send("GET", "/v1/search?name");
        assertEquals(400, flagForm.statusCode(), "proxy rejects a flag-form required parameter");
        assertEquals(2, fake.totalRequests());
    }

    @Test
    void proxyErrorsOverHttp() throws Exception {
        NimbusConfig config = startFakeAndConfig();
        upstreamClient = new HttpUpstreamClient(config.upstreamConnectTimeout(), config.upstreamDeadline(),
                config.upstreamMaxResponseBytes());
        startProxy(config, upstreamClient);

        HttpResponse<String> notFound = send("GET", "/v1/nope?latitude=1&longitude=2");
        assertEquals(404, notFound.statusCode());
        assertEquals("proxy", json(notFound).get("source").getAsString());
        assertEquals(404, send("GET", "/").statusCode());

        HttpResponse<String> post = send("POST", "/v1/forecast?latitude=1&longitude=2");
        assertEquals(405, post.statusCode());
        assertEquals("GET", post.headers().firstValue("Allow").orElseThrow());

        HttpResponse<String> tooLong = send("GET", "/v1/search?name=" + "a".repeat(config.maxQueryLength()));
        assertEquals(414, tooLong.statusCode());

        assertEquals(400, send("GET", "/v1/forecast?latitude=1").statusCode());
        assertEquals(0, fake.totalRequests());
    }

    @Test
    void healthListsUpstreams() throws Exception {
        NimbusConfig config = startFakeAndConfig();
        startProxy(config, new FakeUpstreamClient());

        HttpResponse<String> health = send("GET", "/health");

        assertEquals(200, health.statusCode());
        JsonObject body = json(health);
        assertEquals("ok", body.get("status").getAsString());
        assertEquals(fake.forecastUrl().toString(), body.getAsJsonArray("upstreams").get(0).getAsString());
        assertEquals(fake.searchUrl().toString(), body.getAsJsonArray("upstreams").get(1).getAsString());
        assertEquals(404, send("GET", "/healthz").statusCode());
        assertEquals(405, send("POST", "/health").statusCode());
    }

    @Test
    void handlerExceptionIs500AndServerKeepsWorking() throws Exception {
        NimbusConfig config = startFakeAndConfig();
        FakeUpstreamClient upstream = new FakeUpstreamClient()
                .enqueue(uri -> {
                    throw new IllegalStateException("injected bug");
                });
        startProxy(config, upstream);

        HttpResponse<String> broken = send("GET", "/v1/forecast?latitude=1&longitude=2");
        assertEquals(500, broken.statusCode());
        JsonObject body = json(broken);
        assertTrue(body.get("error").getAsBoolean());
        assertEquals("proxy", body.get("source").getAsString());

        HttpResponse<String> next = send("GET", "/v1/forecast?latitude=1&longitude=2");
        assertEquals(200, next.statusCode());
        assertEquals("MISS", next.headers().firstValue("X-Cache").orElseThrow());
    }

    @Test
    void upstreamTimeoutIs504OverHttp() throws Exception {
        NimbusConfig config = startFakeAndConfig();
        fake.options(fake.options().withMode(FakeUpstreamServer.Mode.HANG));
        NimbusConfig shortDeadline = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", config.forecastUpstream().toString(),
                "nimbus.geocoding.upstream", config.geocodingUpstream().toString(),
                "nimbus.upstream.deadlineMs", "500"));
        upstreamClient = new HttpUpstreamClient(shortDeadline.upstreamConnectTimeout(),
                shortDeadline.upstreamDeadline(), shortDeadline.upstreamMaxResponseBytes());
        startProxy(shortDeadline, upstreamClient);

        HttpResponse<String> response = send("GET", "/v1/forecast?latitude=1&longitude=2");

        assertEquals(504, response.statusCode());
        assertEquals("proxy", json(response).get("source").getAsString());
    }
}
