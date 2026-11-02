package dev.nimbus.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.MetricsSnapshot;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.ProxyFixture;

import java.nio.charset.StandardCharsets;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C4 (PLAN.md, Section 7.3):
 * 200 threads, 20 keys, FakeUpstreamClient &rarr; All 200 succeed.
 * Each body matches its key. I1&ndash;I3 hold exactly. Upstream calls &ge; 20 (logged).
 */
@Timeout(15)
class ProxyServiceConcurrencyTest {

    private static final System.Logger LOG = System.getLogger(ProxyServiceConcurrencyTest.class.getName());
    private static final int THREADS = 200;
    private static final int KEYS = 20;

    @RepeatedTest(20)
    void concurrentRequestsForKeysMaintainInvariants() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.ratelimit.client.capacity", "1000",
                "nimbus.upstream.perMinute", "1000",
                "nimbus.upstream.perHour", "10000",
                "nimbus.upstream.perDay", "100000",
                "nimbus.upstream.maxConcurrent", "200"));
        ProxyFixture f = ProxyFixture.create(config, upstream, InstantSource.system());

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ProxyResponse>> futures = new ArrayList<>();
        List<Integer> keyIndices = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                final int keyIndex = threadId % KEYS;
                keyIndices.add(keyIndex);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    String query = "latitude=" + keyIndex + "&longitude=0";
                    String client = "192.0.2." + (threadId + 1);
                    return f.service().handle(new ProxyRequest("GET", "/v1/forecast", query, client));
                }));
            }

            startGate.countDown();

            List<ProxyResponse> responses = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                ProxyResponse resp = futures.get(t).get();
                responses.add(resp);
                assertEquals(200, resp.status(), "Request " + t + " must succeed with 200");
                int expectedKey = keyIndices.get(t);
                String body = new String(resp.body(), StandardCharsets.UTF_8);
                assertTrue(body.contains("latitude=" + expectedKey),
                        "Response body must match requested key " + expectedKey + ", got: " + body);
            }

            MetricsSnapshot s = f.metrics().snapshot();
            int calls = upstream.callCount();
            LOG.log(System.Logger.Level.INFO, "C4 upstream calls made: " + calls + " for 200 requests across 20 keys");
            assertTrue(calls >= KEYS, "Upstream calls (" + calls + ") must be at least key count (" + KEYS + ")");

            // Invariant I1: requests.total == sum of outcomes
            long sumOutcomes = s.requests().outcomes().values().stream().mapToLong(Long::longValue).sum();
            assertEquals(THREADS, s.requests().total(), "I1: requests.total == 200");
            assertEquals(s.requests().total(), sumOutcomes, "I1: requests.total == sum(outcomes)");

            // Invariant I2: upstream.calls == MISS + STALE_AFTER_UPSTREAM_FAILURE + UPSTREAM_ERROR
            long expectedCalls = s.requests().outcomes().get(Outcome.MISS)
                    + s.requests().outcomes().get(Outcome.STALE_AFTER_UPSTREAM_FAILURE)
                    + s.requests().outcomes().get(Outcome.UPSTREAM_ERROR);
            assertEquals(s.upstream().calls(), expectedCalls, "I2: upstream.calls == MISS + STALE_AFTER + UPSTREAM_ERROR");
            assertEquals(calls, s.upstream().calls(), "I2: upstream.calls matches client count");

            // Invariant I3: upstream.calls == upstream.responses + sum(failures)
            MetricsSnapshot.Failures fail = s.upstream().failures();
            long sumFailures = fail.timeout() + fail.connection() + fail.tooLarge();
            assertEquals(s.upstream().calls(), s.upstream().responses() + sumFailures,
                    "I3: upstream.calls == responses + failures");
        }
    }
}
