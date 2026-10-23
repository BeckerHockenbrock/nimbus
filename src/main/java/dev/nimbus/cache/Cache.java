package dev.nimbus.cache;

import java.util.List;

/**
 * A thread-safe LRU cache bounded by entry count and by total weight in bytes (PLAN.md, Section 3.7).
 *
 * <p>Contract, identical for every implementation:
 * <ol>
 *   <li>{@code get(k)} returns the value and marks it most recently used, or returns {@code null}. A miss changes
 *       nothing.</li>
 *   <li>{@code put(k, v)} with weight {@code w}: if {@code w > maxBytes}, nothing is stored, any existing mapping for
 *       {@code k} is removed, and the result is {@code false}. Otherwise {@code k} is inserted or replaced as most
 *       recently used, then least-recently-used entries are evicted while {@code size > maxEntries} or
 *       {@code bytes > maxBytes}, and the result is {@code true}. An entry is never evicted by its own put.</li>
 *   <li>{@code remove} and {@code clear} adjust size and bytes. Only capacity-driven removals count as evictions.</li>
 *   <li>The cache knows nothing about time.</li>
 *   <li>Operations are O(1), amortized over evictions ({@link #keysInLruOrder} excepted).</li>
 * </ol>
 *
 * <p>Implementations take {@code (int maxEntries, long maxBytes, ToLongBiFunction<K,V> weigher)} and reject
 * bounds {@code <= 0}. Keys and values must not be {@code null}.
 */
public interface Cache<K, V> {

    V get(K key);

    boolean put(K key, V value);

    /** Removes the mapping for {@code key} and returns its value, or {@code null} if there was none. */
    V remove(K key);

    void clear();

    int size();

    /** Total weight of the present entries, in bytes. */
    long weightedSize();

    /** Entries removed to respect a bound, since creation. */
    long evictionCount();

    /**
     * A snapshot of the keys from least to most recently used. O(n); for tests and diagnostics. Does not change
     * recency.
     */
    List<K> keysInLruOrder();
}
