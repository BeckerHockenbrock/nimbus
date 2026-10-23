package dev.nimbus.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Latency samples in a lock-free ring buffer holding the most recent {@code window} values (PLAN.md, Section 3.6).
 *
 * <p>Percentiles use the nearest-rank method over the window: sort, then take index {@code ceil(p/100 × n) − 1}.
 * A snapshot taken while samples are being written may miss the ones still in flight.
 */
public final class LatencyRecorder {

    /** Raw statistics in nanoseconds; the percentile fields are {@code null} when the window is empty. */
    public record Snapshot(int count, long totalRecorded, Long p50, Long p95, Long p99, Long max) {
    }

    private static final long EMPTY = -1;

    private final AtomicLongArray samples;
    private final AtomicLong writeIndex = new AtomicLong();

    public LatencyRecorder(int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be > 0");
        }
        samples = new AtomicLongArray(window);
        for (int i = 0; i < window; i++) {
            samples.set(i, EMPTY); // Lets a snapshot skip slots that are claimed but not yet written.
        }
    }

    public void record(long nanos) {
        long index = writeIndex.getAndIncrement();
        samples.set((int) (index % samples.length()), Math.max(0, nanos));
    }

    public Snapshot snapshot() {
        long total = writeIndex.get();
        int filled = (int) Math.min(total, samples.length());
        long[] values = new long[filled];
        int count = 0;
        for (int i = 0; i < filled; i++) {
            long v = samples.get(i);
            if (v != EMPTY) {
                values[count++] = v;
            }
        }
        if (count == 0) {
            return new Snapshot(0, total, null, null, null, null);
        }
        long[] window = Arrays.copyOf(values, count);
        Arrays.sort(window);
        return new Snapshot(count, total, percentile(window, 50), percentile(window, 95), percentile(window, 99),
                window[count - 1]);
    }

    /** Nearest rank, in integer arithmetic so that, for example, p95 of 100 samples is exactly rank 95. */
    static long percentile(long[] sorted, int p) {
        int n = sorted.length;
        int rank = (int) (((long) p * n + 99) / 100); // ceil(p × n / 100)
        return sorted[Math.max(rank, 1) - 1];
    }
}
