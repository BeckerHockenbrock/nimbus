package dev.nimbus.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.MetricsSnapshot;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.ProxyFixture;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C8 (PLAN.md, Section 7.3):
 * maxConcurrent=4, 50 concurrent misses, fake upstream that blocks &rarr;
 * inFlight peak (tracked by the fake) &le; 4; the rest are UNAVAILABLE/stale; I1&ndash;I3 hold.
 */
@Timeout(15)
class UpstreamConcurrencyCapTest {

    private static final int MAX_CONCURRENT = 4;
    private static final int THREADS = 50;

    @RepeatedTest(20)
    void upstreamConcurrencyCapEnforcedUnderLoad() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.upstream.maxConcurrent", String.valueOf(MAX_CONCURRENT),
                "nimbus.upstream.permitWaitMs", "10",
                "nimbus.ratelimit.client.capacity", "1000",
                "nimbus.upstream.perMinute", "1000",
                "nimbus.upstream.perHour", "10000",
                "nimbus.upstream.perDay", "100000"));
        ProxyFixture f = ProxyFixture.create(config, upstream, InstantSource.system());

        CountDownLatch blockUpstream = new CountDownLatch(1);
        upstream.gate(blockUpstream);

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ProxyResponse>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    String query = "latitude=" + threadId + "&longitude=0";
                    String client = "192.0.2." + (threadId + 1);
                    return f.service().handle(new ProxyRequest("GET", "/v1/forecast", query, client));
                }));
            }

            startGate.countDown();

            // Wait until 4 calls are holding permits and blocked in upstream
            upstream.awaitCallCount(MAX_CONCURRENT, java.time.Duration.ofSeconds(5));

            // Wait for non-permit-holding requests to timeout waiting for permit and turn away
            Thread.sleep(100);

            // Release the blocked upstream calls
            blockUpstream.countDown();

            List<ProxyResponse> responses = new ArrayList<>();
            for (Future<ProxyResponse> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            // Verify in-flight peak
            assertTrue(upstream.peakConcurrentCalls() <= MAX_CONCURRENT,
                    "Peak concurrent calls (" + upstream.peakConcurrentCalls() + ") must be <= " + MAX_CONCURRENT);

            long misses = responses.stream().filter(r -> r.outcome() == Outcome.MISS).count();
            long unavailable = responses.stream().filter(r -> r.outcome() == Outcome.UNAVAILABLE).count();

            assertEquals(MAX_CONCURRENT, misses, "Exactly maxConcurrent (4) requests succeeded with MISS");
            assertEquals(THREADS - MAX_CONCURRENT, unavailable, "Remaining requests got UNAVAILABLE");

            MetricsSnapshot s = f.metrics().snapshot();
            assertEquals(THREADS, s.requests().total(), "I1: requests.total == 50");
            assertEquals(MAX_CONCURRENT, s.upstream().calls(), "I2: upstream.calls == 4");
            assertEquals(MAX_CONCURRENT, s.upstream().responses(), "I3: upstream.responses == 4");
            assertEquals(0, s.upstream().inFlight(), "No in-flight calls remain");
        }
    }
}
