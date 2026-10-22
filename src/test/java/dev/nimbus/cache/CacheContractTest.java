package dev.nimbus.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.testutil.ReferenceLruModel;

import java.util.List;
import java.util.Random;
import java.util.function.ToLongBiFunction;

import org.junit.jupiter.api.Test;

/**
 * The {@link Cache} contract (PLAN.md, Section 3.7). Every implementation's test extends this class, so the
 * baseline and the Track 2 cache are held to exactly the same rules.
 */
abstract class CacheContractTest {

    /** The test weigher: {@code value.length() + key.length()}. */
    protected static final ToLongBiFunction<String, String> WEIGHER = (key, value) -> value.length() + key.length();

    protected abstract Cache<String, String> newCache(int maxEntries, long maxBytes);

    /** Asserts I5 plus the internal consistency of the byte count. */
    private static void assertBounds(Cache<String, String> cache, int maxEntries, long maxBytes) {
        assertTrue(cache.size() <= maxEntries, "size " + cache.size() + " <= " + maxEntries);
        assertTrue(cache.weightedSize() <= maxBytes, "bytes " + cache.weightedSize() + " <= " + maxBytes);
        assertEquals(cache.keysInLruOrder().size(), cache.size());
    }

    @Test
    void getOnEmptyCacheReturnsNull() {
        Cache<String, String> cache = newCache(10, 1000);
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
        assertEquals(0, cache.weightedSize());
        assertEquals(List.of(), cache.keysInLruOrder());
    }

    @Test
    void putThenGet() {
        Cache<String, String> cache = newCache(10, 1000);
        assertTrue(cache.put("a", "apple"));
        assertEquals("apple", cache.get("a"));
        assertEquals(1, cache.size());
        assertEquals(6, cache.weightedSize());
    }

    @Test
    void replaceUpdatesValueAndBytes() {
        Cache<String, String> cache = newCache(10, 1000);
        cache.put("a", "apple");
        assertTrue(cache.put("a", "apricot"));
        assertEquals("apricot", cache.get("a"));
        assertEquals(1, cache.size());
        assertEquals(8, cache.weightedSize());
        assertEquals(0, cache.evictionCount(), "replacing is not an eviction");
    }

    @Test
    void replaceMakesTheEntryMostRecentlyUsed() {
        Cache<String, String> cache = newCache(10, 1000);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("a", "3");
        assertEquals(List.of("b", "a"), cache.keysInLruOrder());
    }

    @Test
    void getProtectsAnEntryFromEviction() {
        Cache<String, String> cache = newCache(2, 1000);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.get("a");
        cache.put("c", "3");
        assertEquals("1", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(1, cache.evictionCount());
    }

    @Test
    void missChangesNothing() {
        Cache<String, String> cache = newCache(10, 1000);
        cache.put("a", "1");
        cache.put("b", "2");
        assertNull(cache.get("zzz"));
        assertEquals(List.of("a", "b"), cache.keysInLruOrder());
    }

    @Test
    void keySnapshotDoesNotChangeRecency() {
        Cache<String, String> cache = newCache(2, 1000);
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals(List.of("a", "b"), cache.keysInLruOrder());
        cache.put("c", "3");
        assertEquals(List.of("b", "c"), cache.keysInLruOrder(), "a was still the eldest");
    }

    @Test
    void entryBoundEvictsTheLeastRecentlyUsed() {
        Cache<String, String> cache = newCache(3, 1000);
        for (String k : List.of("a", "b", "c", "d")) {
            cache.put(k, k);
        }
        assertEquals(List.of("b", "c", "d"), cache.keysInLruOrder());
        assertEquals(1, cache.evictionCount());
        assertEquals(6, cache.weightedSize());
    }

    @Test
    void byteBoundEvictsSeveralEntriesToFitOneBigEntry() {
        Cache<String, String> cache = newCache(100, 30);
        cache.put("a", "123456789"); // 10
        cache.put("b", "123456789"); // 10
        cache.put("c", "123456789"); // 10 -> 30 total
        assertTrue(cache.put("d", "1234567890123456789")); // 20: a and b must go
        assertEquals(List.of("c", "d"), cache.keysInLruOrder());
        assertEquals(30, cache.weightedSize());
        assertEquals(2, cache.evictionCount());
    }

    @Test
    void entryHeavierThanMaxBytesIsRejectedAndRemovesTheOldMapping() {
        Cache<String, String> cache = newCache(10, 20);
        cache.put("a", "old");
        cache.put("b", "keep");
        assertFalse(cache.put("a", "x".repeat(20))); // 21 > 20
        assertNull(cache.get("a"), "older value must not survive a rejected newer one");
        assertEquals("keep", cache.get("b"));
        assertEquals(5, cache.weightedSize());
        assertEquals(0, cache.evictionCount(), "a rejection is not an eviction");
    }

    @Test
    void entryOfExactlyMaxBytesFits() {
        Cache<String, String> cache = newCache(10, 20);
        cache.put("a", "1");
        assertTrue(cache.put("b", "x".repeat(19)));
        assertEquals(List.of("b"), cache.keysInLruOrder());
        assertEquals(20, cache.weightedSize());
        assertEquals(1, cache.evictionCount());
    }

    @Test
    void bothBoundsTogether() {
        Cache<String, String> cache = newCache(3, 25);
        cache.put("a", "1234"); // 5
        cache.put("b", "1234"); // 5
        cache.put("c", "1234"); // 5
        cache.put("d", "1234"); // entry bound: evicts a
        assertEquals(List.of("b", "c", "d"), cache.keysInLruOrder());
        cache.put("e", "123456789012345"); // 16: byte bound needs 31 - 25 -> evicts b and c
        assertEquals(List.of("d", "e"), cache.keysInLruOrder());
        assertEquals(21, cache.weightedSize());
        assertEquals(3, cache.evictionCount());
        assertBounds(cache, 3, 25);
    }

    @Test
    void removeAndClearAdjustSizeAndBytesButAreNotEvictions() {
        Cache<String, String> cache = newCache(10, 1000);
        cache.put("a", "apple");
        cache.put("b", "banana");
        assertEquals("apple", cache.remove("a"));
        assertNull(cache.remove("a"));
        assertEquals(1, cache.size());
        assertEquals(7, cache.weightedSize());
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.weightedSize());
        assertEquals(0, cache.evictionCount());
        assertTrue(cache.put("c", "cherry"), "usable after clear");
        assertEquals(7, cache.weightedSize());
    }

    @Test
    void nonPositiveBoundsThrow() {
        assertThrows(IllegalArgumentException.class, () -> newCache(0, 100));
        assertThrows(IllegalArgumentException.class, () -> newCache(10, 0));
        assertThrows(IllegalArgumentException.class, () -> newCache(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> newCache(10, -5));
    }

    /**
     * 10,000 random operations with a fixed seed. After each one, the result, size, bytes, eviction count, and key
     * order match the reference model, and I5 holds.
     */
    @Test
    void matchesTheReferenceModel() {
        int maxEntries = 20;
        long maxBytes = 200;
        Cache<String, String> cache = newCache(maxEntries, maxBytes);
        ReferenceLruModel<String, String> model = new ReferenceLruModel<>(maxEntries, maxBytes, WEIGHER);
        Random random = new Random(20261022);

        for (int op = 0; op < 10_000; op++) {
            String key = "k" + random.nextInt(50);
            int kind = random.nextInt(100);
            String description;
            if (kind < 45) {
                description = "get " + key;
                assertEquals(model.get(key), cache.get(key), description);
            } else if (kind < 90) {
                // Mostly small values, sometimes big ones, occasionally heavier than maxBytes.
                int length = random.nextInt(10) == 0 ? 50 + random.nextInt(200) : random.nextInt(15);
                String value = key + ":" + "v".repeat(length);
                description = "put " + key + " (" + value.length() + " chars)";
                assertEquals(model.put(key, value), cache.put(key, value), description);
            } else if (kind < 99) {
                description = "remove " + key;
                assertEquals(model.remove(key), cache.remove(key), description);
            } else {
                description = "clear";
                model.clear();
                cache.clear();
            }
            String at = "after op " + op + " (" + description + ")";
            assertEquals(model.size(), cache.size(), at);
            assertEquals(model.weightedSize(), cache.weightedSize(), at);
            assertEquals(model.evictionCount(), cache.evictionCount(), at);
            assertEquals(model.keysInLruOrder(), cache.keysInLruOrder(), at);
            assertBounds(cache, maxEntries, maxBytes);
        }
        assertTrue(cache.evictionCount() > 100, "the run exercised evictions: " + cache.evictionCount());
    }
}
