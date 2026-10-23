package dev.nimbus.testutil;

import dev.nimbus.cache.Cache;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongBiFunction;

/**
 * A deliberately simple O(n) LRU with the same entry and byte rules as the {@link Cache} contract. Index 0 is the
 * least recently used entry. Used as the model that real implementations are checked against.
 */
public final class ReferenceLruModel<K, V> implements Cache<K, V> {

    private record Item<K, V>(K key, V value, long weight) {
    }

    private final int maxEntries;
    private final long maxBytes;
    private final ToLongBiFunction<K, V> weigher;
    private final List<Item<K, V>> items = new ArrayList<>();
    private long evictions;

    public ReferenceLruModel(int maxEntries, long maxBytes, ToLongBiFunction<K, V> weigher) {
        if (maxEntries <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("bounds must be > 0");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
    }

    private int indexOf(K key) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public V get(K key) {
        int i = indexOf(key);
        if (i < 0) {
            return null;
        }
        Item<K, V> item = items.remove(i);
        items.add(item);
        return item.value();
    }

    @Override
    public boolean put(K key, V value) {
        long weight = weigher.applyAsLong(key, value);
        int i = indexOf(key);
        if (i >= 0) {
            items.remove(i);
        }
        if (weight > maxBytes) {
            return false;
        }
        items.add(new Item<>(key, value, weight));
        while (items.size() > maxEntries || weightedSize() > maxBytes) {
            items.remove(0);
            evictions++;
        }
        return true;
    }

    @Override
    public V remove(K key) {
        int i = indexOf(key);
        return i < 0 ? null : items.remove(i).value();
    }

    @Override
    public void clear() {
        items.clear();
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public long weightedSize() {
        return items.stream().mapToLong(Item::weight).sum();
    }

    @Override
    public long evictionCount() {
        return evictions;
    }

    @Override
    public List<K> keysInLruOrder() {
        return items.stream().map(Item::key).toList();
    }
}
