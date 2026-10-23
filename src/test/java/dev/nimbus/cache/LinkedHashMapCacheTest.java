package dev.nimbus.cache;

class LinkedHashMapCacheTest extends CacheContractTest {

    @Override
    protected Cache<String, String> newCache(int maxEntries, long maxBytes) {
        return new LinkedHashMapCache<>(maxEntries, maxBytes, WEIGHER);
    }
}
