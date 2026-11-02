package dev.nimbus.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongBiFunction;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C1 (PLAN.md, Section 7.3):
 * 32 threads &times; 10,000 random get/put/remove ops on 1,000 keys;
 * maxEntries=100, maxBytes small enough to force byte evictions.
 */
@Timeout(30)
class CacheConcurrencyTest {

    private static final int THREADS = 32;
    private static final int OPS_PER_THREAD = 10_000;
    private static final int KEY_COUNT = 1_000;
    private static final int MAX_ENTRIES = 100;
    private static final long MAX_BYTES = 2_000;
    private static final ToLongBiFunction<String, String> WEIGHER =
            (key, val) -> (long) key.length() + val.length();

    protected Cache<String, String> createCache(int maxEntries, long maxBytes) {
        return new LinkedHashMapCache<>(maxEntries, maxBytes, WEIGHER);
    }

    @RepeatedTest(20)
    void concurrentOperationsMaintainInvariants() throws Exception {
        Cache<String, String> cache = createCache(MAX_ENTRIES, MAX_BYTES);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> samplerError = new AtomicReference<>();

        Thread sampler = new Thread(() -> {
            try {
                startGate.await();
                while (running.get()) {
                    int size = cache.size();
                    long bytes = cache.weightedSize();
                    if (size > MAX_ENTRIES) {
                        samplerError.compareAndSet(null,
                                new AssertionError("Sampled size " + size + " > maxEntries " + MAX_ENTRIES));
                    }
                    if (bytes > MAX_BYTES) {
                        samplerError.compareAndSet(null,
                                new AssertionError("Sampled bytes " + bytes + " > maxBytes " + MAX_BYTES));
                    }
                    Thread.yield();
                }
            } catch (Throwable t) {
                samplerError.compareAndSet(null, t);
            }
        }, "cache-sampler");
        sampler.start();

        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String key = "key-" + rng.nextInt(KEY_COUNT);
                        int action = rng.nextInt(10);
                        if (action < 5) { // 50% get
                            String val = cache.get(key);
                            if (val != null) {
                                assertTrue(val.startsWith("val-" + key + "-"),
                                        "Read value " + val + " was not written for key " + key);
                            }
                        } else if (action < 9) { // 40% put
                            String val = "val-" + key + "-" + threadId + "-" + i;
                            cache.put(key, val);
                        } else { // 10% remove
                            cache.remove(key);
                        }
                    }
                    return null;
                }));
            }

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            running.set(false);
            sampler.join(5000);
        }

        if (samplerError.get() != null) {
            throw new AssertionError("Invariant violated during concurrent run", samplerError.get());
        }

        assertTrue(cache.size() <= MAX_ENTRIES, "size <= maxEntries at end");
        assertTrue(cache.weightedSize() <= MAX_BYTES, "weightedSize <= maxBytes at end");

        // Recompute sum of weights by iterating present keys
        long sum = 0;
        List<String> keys = cache.keysInLruOrder();
        assertEquals(cache.size(), keys.size(), "keys count matches size");
        for (String k : keys) {
            String v = cache.get(k);
            if (v != null) {
                sum += WEIGHER.applyAsLong(k, v);
            }
        }
        assertEquals(sum, cache.weightedSize(), "weightedSize == sum of present entries' weights");
    }
}
