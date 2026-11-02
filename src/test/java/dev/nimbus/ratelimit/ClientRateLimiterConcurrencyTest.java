package dev.nimbus.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C3 (PLAN.md, Section 7.3):
 * 64 threads, 10 client IDs, created concurrently &rarr;
 * Exactly 10 buckets; per-client successes == capacity.
 */
@Timeout(10)
class ClientRateLimiterConcurrencyTest {

    private static final int THREADS = 64;
    private static final int CLIENTS = 10;
    private static final int CAPACITY = 15;
    private static final int ATTEMPTS_PER_THREAD = 20;

    @RepeatedTest(20)
    void perClientLimitsAndBucketCountUnderContention() throws Exception {
        InstantSource frozenClock = InstantSource.fixed(Instant.now());
        ClientRateLimiter limiter = new ClientRateLimiter(CAPACITY, 1.0, frozenClock);

        CountDownLatch startGate = new CountDownLatch(1);
        Map<String, AtomicInteger> perClientSuccesses = new ConcurrentHashMap<>();
        for (int i = 0; i < CLIENTS; i++) {
            perClientSuccesses.put("client-" + i, new AtomicInteger());
        }

        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                        String clientId = "client-" + ((threadId + i) % CLIENTS);
                        RateLimitDecision decision = limiter.tryAcquire(clientId);
                        if (decision.allowed()) {
                            perClientSuccesses.get(clientId).incrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get();
            }

            assertEquals(CLIENTS, limiter.bucketCount(), "Exactly 10 buckets created");
            for (int i = 0; i < CLIENTS; i++) {
                String clientId = "client-" + i;
                assertEquals(CAPACITY, perClientSuccesses.get(clientId).get(),
                        "Successes for " + clientId + " must equal capacity");
            }
        }
    }
}
