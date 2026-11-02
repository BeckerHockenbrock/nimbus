package dev.nimbus.cache;

import org.junit.jupiter.api.Disabled;

@Disabled("Track 2: owner implements custom LRU")
class LinkedListLruCacheTest extends CacheContractTest {

    @Override
    protected Cache<String, String> newCache(int maxEntries, long maxBytes) {
        return new LinkedListLruCache<>(maxEntries, maxBytes, WEIGHER);
    }
}
