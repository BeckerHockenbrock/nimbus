package dev.nimbus.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LatencyRecorderTest {

    @Test
    void oneToHundredGivesNearestRankPercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(4096);
        List<Long> samples = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            samples.add(i);
        }
        Collections.shuffle(samples, new Random(7)); // Order of recording doesn't matter.
        samples.forEach(recorder::record);

        LatencyRecorder.Snapshot s = recorder.snapshot();

        assertEquals(100, s.count());
        assertEquals(100, s.totalRecorded());
        assertEquals(50L, s.p50());
        assertEquals(95L, s.p95());
        assertEquals(99L, s.p99());
        assertEquals(100L, s.max());
    }

    @Test
    void aSingleSampleIsEveryPercentile() {
        LatencyRecorder recorder = new LatencyRecorder(16);
        recorder.record(42);

        LatencyRecorder.Snapshot s = recorder.snapshot();

        assertEquals(new LatencyRecorder.Snapshot(1, 1, 42L, 42L, 42L, 42L), s);
    }

    @Test
    void emptyWindowGivesNulls() {
        LatencyRecorder.Snapshot s = new LatencyRecorder(16).snapshot();

        assertEquals(0, s.count());
        assertEquals(0, s.totalRecorded());
        assertNull(s.p50());
        assertNull(s.p95());
        assertNull(s.p99());
        assertNull(s.max());
    }

    @Test
    void wraparoundKeepsOnlyTheLastNSamples() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        for (long i = 1; i <= 25; i++) {
            recorder.record(i * 1000);
        }

        LatencyRecorder.Snapshot s = recorder.snapshot();

        assertEquals(10, s.count(), "window size");
        assertEquals(25, s.totalRecorded(), "all-time count keeps going");
        assertEquals(25_000L, s.max());
        assertEquals(20_000L, s.p50(), "window is 16..25; rank 5 is 20");
        assertEquals(25_000L, s.p95());
    }

    @Test
    void negativeDurationsAreClampedToZero() {
        LatencyRecorder recorder = new LatencyRecorder(4);
        recorder.record(-5);
        assertEquals(0L, recorder.snapshot().max());
    }

    @Test
    void percentileOfSmallWindows() {
        assertEquals(1, LatencyRecorder.percentile(new long[] {1, 2}, 50));
        assertEquals(2, LatencyRecorder.percentile(new long[] {1, 2}, 95));
        assertEquals(2, LatencyRecorder.percentile(new long[] {1, 2, 3}, 50));
    }

    @Test
    void windowMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyRecorder(0));
    }

    @Test
    void millisecondConversionHasThreeDecimals() {
        LatencyRecorder recorder = new LatencyRecorder(4);
        recorder.record(1_234_567); // 1.234567 ms

        MetricsSnapshot.Latency latency = MetricsSnapshot.Latency.of(recorder.snapshot());

        assertEquals(1.235, latency.p50());
        assertEquals(1, latency.count());
        assertNull(MetricsSnapshot.Latency.of(new LatencyRecorder(4).snapshot()).max());
    }
}
