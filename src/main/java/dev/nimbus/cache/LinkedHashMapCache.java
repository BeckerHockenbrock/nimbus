package dev.nimbus.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongBiFunction;

/**
 * Baseline {@link Cache}: an access-ordered {@link LinkedHashMap} guarded by one {@link ReentrantLock}.
 *
 * <p>Eviction walks the map from the eldest entry while over a bound. {@code removeEldestEntry} isn't used because it
 * removes at most one entry per {@code put} and can't enforce a byte bound. Every {@code get} reorders the list, so
 * reads take the lock too.
 */
public final class LinkedHashMapCache<K, V> implements Cache<K, V> {

    private record Weighted<V>(V value, long weight) {
    }

    private final int maxEntries;
    private final long maxBytes;
    private final ToLongBiFunction<K, V> weigher;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, Weighted<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    private long evictions;

    public LinkedHashMapCache(int maxEntries, long maxBytes, ToLongBiFunction<K, V> weigher) {
        if (maxEntries <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("maxEntries and maxBytes must be > 0, got " + maxEntries + ", " + maxBytes);
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.weigher = Objects.requireNonNull(weigher, "weigher");
    }

    @Override
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Weighted<V> w = map.get(key); // Access order: moves the entry to the most-recently-used end.
            return w == null ? null : w.value();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long weight = weigher.applyAsLong(key, value);
        if (weight < 0) {
            throw new IllegalArgumentException("negative weight " + weight + " for key " + key);
        }
        lock.lock();
        try {
            if (weight > maxBytes) {
                Weighted<V> old = map.remove(key);
                if (old != null) {
                    bytes -= old.weight();
                }
                return false;
            }
            Weighted<V> old = map.put(key, new Weighted<>(value, weight));
            bytes += weight - (old == null ? 0 : old.weight());
            Iterator<Map.Entry<K, Weighted<V>>> eldestFirst = map.entrySet().iterator();
            while (map.size() > maxEntries || bytes > maxBytes) {
                Map.Entry<K, Weighted<V>> eldest = eldestFirst.next();
                eldestFirst.remove();
                bytes -= eldest.getValue().weight();
                evictions++;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Weighted<V> old = map.remove(key);
            if (old == null) {
                return null;
            }
            bytes -= old.weight();
            return old.value();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            map.clear();
            bytes = 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long weightedSize() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long evictionCount() {
        lock.lock();
        try {
            return evictions;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<K> keysInLruOrder() {
        lock.lock();
        try {
            return List.copyOf(map.keySet()); // Iterating the key set is not an access.
        } finally {
            lock.unlock();
        }
    }
}
