package dev.nimbus.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.ratelimit.UpstreamBudget;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C6 (PLAN.md, Section 7.3):
 * 64 threads &times; 10,000 record/increment calls &rarr;
 * Exact totals; snapshots taken during writes never throw.
 */
@Timeout(15)
class MetricsConcurrencyTest {

    private static final int THREADS = 64;
    private static final int OPS_PER_THREAD = 10_000;

    @RepeatedTest(20)
    void concurrentMetricsUpdatesAndSnapshots() throws Exception {
        NimbusConfig config = NimbusConfig.defaults();
        LinkedHashMapCache<String, String> cache = new LinkedHashMapCache<>(100, 10_000, (k, v) -> 1);
        InstantSource clock = InstantSource.system();
        UpstreamBudget budget = UpstreamBudget.from(config, clock);
        ProxyMetrics metrics = new ProxyMetrics(config, cache, budget, clock);

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> snapshotError = new AtomicReference<>();

        // Background thread continuously taking snapshots during concurrent writes
        Thread reader = new Thread(() -> {
            try {
                startGate.await();
                while (running.get()) {
                    MetricsSnapshot snap = metrics.snapshot();
                    assertNotNull(snap);
                    assertNotNull(snap.requests());
                    assertNotNull(snap.upstream());
                    Thread.yield();
                }
            } catch (Throwable t) {
                snapshotError.compareAndSet(null, t);
            }
        }, "metrics-snapshotter");
        reader.start();

        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    Outcome outcome = Outcome.values()[threadId % Outcome.values().length];
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        metrics.recordOutcome(outcome);
                        metrics.recordLatency(outcome, 1_000_000L);
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            running.set(false);
            reader.join(5000);
        }

        if (snapshotError.get() != null) {
            throw new AssertionError("Snapshot threw during concurrent writes", snapshotError.get());
        }

        MetricsSnapshot finalSnap = metrics.snapshot();
        long expectedTotal = (long) THREADS * OPS_PER_THREAD;
        assertEquals(expectedTotal, finalSnap.requests().total(), "Total requests matches exactly");

        long sumOutcomes = finalSnap.requests().outcomes().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(expectedTotal, sumOutcomes, "Sum of outcomes matches exactly");

        // Verify per-outcome count
        for (Outcome o : Outcome.values()) {
            int threadsForOutcome = 0;
            for (int t = 0; t < THREADS; t++) {
                if (Outcome.values()[t % Outcome.values().length] == o) {
                    threadsForOutcome++;
                }
            }
            long expected = (long) threadsForOutcome * OPS_PER_THREAD;
            assertEquals(expected, finalSnap.requests().outcomes().get(o), "Outcome " + o + " count matches");
        }
    }
}
