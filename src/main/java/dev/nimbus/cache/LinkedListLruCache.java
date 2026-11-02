package dev.nimbus.cache;

import java.util.List;
import java.util.Objects;
import java.util.function.ToLongBiFunction;

/**
 * Stub for the Track 2 custom LRU cache (PLAN.md, Phase 9, Track 2).
 *
 * <p>Invariants to maintain when implementing:
 * <ol>
 *   <li><b>LRU ordering:</b> A doubly-linked list with sentinel head and tail nodes. The most recently used
 *       entry sits at the head, and eviction removes from the tail.</li>
 *   <li><b>Read recency:</b> {@link #get(Object)} moves the accessed entry to the head (most recently used).
 *       A miss changes nothing.</li>
 *   <li><b>Size and memory bounds:</b> {@link #size()} &le; {@code maxEntries} and
 *       {@link #weightedSize()} &le; {@code maxBytes} at all times.</li>
 *   <li><b>Byte-based eviction:</b> When {@link #put(Object, Object)} exceeds {@code maxBytes} for a single entry,
 *       nothing is stored, any existing mapping is removed, and it returns {@code false}. Otherwise, entries are
 *       evicted from the tail while {@code size > maxEntries} or {@code weightedSize > maxBytes}.</li>
 *   <li><b>Eviction count:</b> Only capacity-driven removals increment {@link #evictionCount()}. Explicit
 *       removals via {@link #remove(Object)} and replacements do not.</li>
 *   <li><b>Thread safety:</b> All operations are guarded by a {@link java.util.concurrent.locks.ReentrantLock}.
 *       No I/O is performed under locks.</li>
 *   <li><b>O(1) operations:</b> {@code get}, {@code put}, and {@code remove} are O(1) amortized over evictions.</li>
 * </ol>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public final class LinkedListLruCache<K, V> implements Cache<K, V> {

    public LinkedListLruCache(int maxEntries, long maxBytes, ToLongBiFunction<K, V> weigher) {
        if (maxEntries <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("maxEntries and maxBytes must be > 0, got " + maxEntries + ", " + maxBytes);
        }
        Objects.requireNonNull(weigher, "weigher");
    }

    @Override
    public V get(K key) {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public boolean put(K key, V value) {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public V remove(K key) {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public long weightedSize() {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public long evictionCount() {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }

    @Override
    public List<K> keysInLruOrder() {
        throw new UnsupportedOperationException("Track 2: owner implements custom LRU");
    }
}
