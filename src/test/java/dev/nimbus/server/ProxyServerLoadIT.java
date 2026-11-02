package dev.nimbus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C7 (PLAN.md, Section 7.3):
 * Real server, 500 virtual-thread clients, 50 keys, fake upstream &rarr;
 * No 5xx or connection resets; I4 holds; finishes within @Timeout(30).
 */
@Timeout(30)
class ProxyServerLoadIT {

    private static final int CLIENTS = 500;
    private static final int KEYS = 50;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private FakeUpstreamServer fake;
    private HttpUpstreamClient upstreamClient;
    private ProxyServer server;
    private ProxyFixture fixture;

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

    @Test
    void loadUnderVirtualThreadsMaintainsInvariants() throws Exception {
        fake = FakeUpstreamServer.start(0, FakeUpstreamServer.Options.defaults());
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.port", "0",
                "nimbus.forecast.upstream", fake.forecastUrl().toString(),
                "nimbus.geocoding.upstream", fake.searchUrl().toString(),
                "nimbus.ratelimit.client.capacity", "1000",
                "nimbus.ratelimit.client.refillPerSecond", "1000",
                "nimbus.upstream.perMinute", "10000",
                "nimbus.upstream.perHour", "100000",
                "nimbus.upstream.perDay", "1000000",
                "nimbus.upstream.maxConcurrent", "100"));

        upstreamClient = new HttpUpstreamClient(config.upstreamConnectTimeout(), config.upstreamDeadline(),
                config.upstreamMaxResponseBytes());
        fixture = ProxyFixture.create(config, upstreamClient, InstantSource.system());
        server = new ProxyServer(config, fixture.service(), fixture.metrics(), fixture.rateLimiter());
        server.start();

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTS; i++) {
                final int keyId = i % KEYS;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/v1/forecast?latitude=" + keyId + "&longitude=0"))
                            .GET()
                            .build();
                    return http.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }

            startGate.countDown();

            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> response = f.get(20, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), "Expected HTTP 200, no 5xx or error responses");
            }

            // Invariant I4: proxy upstream.calls == fake server's received count
            long proxyCalls = fixture.metrics().snapshot().upstream().calls();
            long fakeReceived = fake.totalRequests();
            assertEquals(fakeReceived, proxyCalls, "I4: proxy upstream.calls == fake upstream totalRequests");
            assertTrue(proxyCalls >= KEYS, "At least one call per distinct key");
        }
    }
}
