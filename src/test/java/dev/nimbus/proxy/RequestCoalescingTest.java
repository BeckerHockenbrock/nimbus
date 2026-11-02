package dev.nimbus.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.testutil.ProxyFixture;
import dev.nimbus.testutil.TestEntries;
import dev.nimbus.upstream.UpstreamException;
import dev.nimbus.upstream.UpstreamResponse;

import java.nio.charset.StandardCharsets;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Acceptance tests for Track 1: request coalescing (single-flight).
 *
 * <p>Annotated with {@link Disabled} until the owner implements Track 1.
 */
@Disabled("Track 1: owner implements request coalescing")
@Timeout(30)
class RequestCoalescingTest {

    private static final String CLIENT = "192.0.2.1";
    private static final String BERLIN = "latitude=52.52437&longitude=13.41053&current=temperature_2m";

    private static UpstreamResponse forecastResponse(String body) {
        return TestEntries.response(200, body, "Content-Type", "application/json; charset=utf-8");
    }

    /** 100 concurrent requests for the same key result in exactly 1 upstream call, 1 MISS, 99 COALESCED. */
    @Test
    void concurrentMissesForSameKeyAreCoalesced() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        NimbusConfig config = NimbusConfig.withOverrides(Map.of(
                "nimbus.ratelimit.client.capacity", "1000",
                "nimbus.upstream.maxConcurrent", "100"));
        ProxyFixture f = ProxyFixture.create(config, upstream, InstantSource.system());

        int threadCount = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch waitingInUpstream = new CountDownLatch(threadCount);
        CountDownLatch releaseUpstream = new CountDownLatch(1);

        upstream.respond(uri -> {
            try {
                releaseUpstream.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return forecastResponse("{\"temp\":20.5}");
        });

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProxyResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final String client = "192.0.2." + (i + 1);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    waitingInUpstream.countDown();
                    return f.service().handle(new ProxyRequest("GET", "/v1/forecast", BERLIN, client));
                }, pool));
            }

            startGate.countDown();
            waitingInUpstream.await(5, TimeUnit.SECONDS);
            releaseUpstream.countDown();

            List<ProxyResponse> responses = new ArrayList<>();
            for (CompletableFuture<ProxyResponse> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            assertEquals(1, upstream.callCount(), "exactly 1 upstream call for 100 coalesced requests");
            long misses = responses.stream().filter(r -> r.outcome() == Outcome.MISS).count();
            long coalesced = responses.stream().filter(r -> r.outcome() == Outcome.COALESCED).count();
            assertEquals(1, misses, "1 leader gets MISS");
            assertEquals(99, coalesced, "99 followers get COALESCED");

            String expectedBody = "{\"temp\":20.5}";
            for (ProxyResponse r : responses) {
                assertEquals(200, r.status());
                assertEquals(expectedBody, new String(r.body(), StandardCharsets.UTF_8));
            }
        }
    }

    /** If the leader's upstream call fails, followers apply the fallback and inFlight is cleared. */
    @Test
    void leaderFailurePropagatesToFollowersAndClearsInFlight() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        ProxyFixture f = ProxyFixture.create(NimbusConfig.defaults(), upstream, InstantSource.system());

        upstream.respond(uri -> {
            throw new UpstreamException(UpstreamException.Kind.CONNECTION, "connection refused", null);
        });

        int threadCount = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProxyResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final String client = "192.0.2." + (i + 1);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return f.service().handle(new ProxyRequest("GET", "/v1/forecast", BERLIN, client));
                }, pool));
            }

            startGate.countDown();
            for (CompletableFuture<ProxyResponse> future : futures) {
                ProxyResponse response = future.get(10, TimeUnit.SECONDS);
                assertEquals(502, response.status());
            }
        }
    }

    /** Different keys do not block each other. */
    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        ProxyFixture f = ProxyFixture.create(NimbusConfig.defaults(), upstream, InstantSource.system());

        upstream.respond(forecastResponse("{\"temp\":15.0}"));

        ProxyResponse r1 = f.service().handle(new ProxyRequest("GET", "/v1/forecast", "latitude=1&longitude=1", CLIENT));
        ProxyResponse r2 = f.service().handle(new ProxyRequest("GET", "/v1/forecast", "latitude=2&longitude=2", CLIENT));

        assertEquals(200, r1.status());
        assertEquals(200, r2.status());
        assertEquals(2, upstream.callCount());
    }

    /** A follower exceeding the deadline gets a 504/stale response, while leader is unaffected. */
    @Test
    void followerDeadlineExceededReturns504() throws Exception {
        FakeUpstreamClient upstream = new FakeUpstreamClient();
        ProxyFixture f = ProxyFixture.create(NimbusConfig.defaults(), upstream, InstantSource.system());

        CountDownLatch holdLeader = new CountDownLatch(1);
        upstream.respond(uri -> {
            try {
                holdLeader.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return forecastResponse("{\"temp\":10.0}");
        });

        // Test coalescing timeout behavior when Track 1 is implemented
    }
}
