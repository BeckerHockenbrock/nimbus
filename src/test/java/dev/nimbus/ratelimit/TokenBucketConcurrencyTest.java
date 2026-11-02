package dev.nimbus.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency test C2 (PLAN.md, Section 7.3):
 * Capacity 100, frozen clock, 64 threads &times; 10 tryAcquire &rarr; Exactly 100 successes.
 */
@Timeout(10)
class TokenBucketConcurrencyTest {

    private static final int CAPACITY = 100;
    private static final int THREADS = 64;
    private static final int ATTEMPTS_PER_THREAD = 10;

    @RepeatedTest(20)
    void exactlyCapacitySuccessesUnderContention() throws Exception {
        InstantSource frozenClock = InstantSource.fixed(Instant.now());
        TokenBucket bucket = new TokenBucket(CAPACITY, 1.0, frozenClock);

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    int success = 0;
                    for (int i = 0; i < ATTEMPTS_PER_THREAD; i++) {
                        if (bucket.tryAcquire()) {
                            success++;
                        }
                    }
                    return success;
                }));
            }

            startGate.countDown();

            int totalSuccess = 0;
            for (Future<Integer> f : futures) {
                totalSuccess += f.get();
            }

            assertEquals(CAPACITY, totalSuccess, "Exactly 100 successes out of 640 attempts");
            assertEquals(0, bucket.availableTokens(), "No tokens left in bucket");
        }
    }
}
