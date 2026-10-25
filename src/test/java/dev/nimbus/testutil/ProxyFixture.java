package dev.nimbus.testutil;

import dev.nimbus.cache.Cache;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.ProxyMetrics;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.ratelimit.ClientRateLimiter;
import dev.nimbus.ratelimit.UpstreamBudget;
import dev.nimbus.upstream.UpstreamClient;

import java.time.InstantSource;
import java.util.concurrent.Semaphore;

/** A {@link ProxyService} and its collaborators, wired from one config the way {@code Main} wires them. */
public record ProxyFixture(NimbusConfig config, Cache<String, CacheEntry> cache, ClientRateLimiter rateLimiter,
                           UpstreamBudget budget, ProxyMetrics metrics, ProxyService service) {

    public static ProxyFixture create(NimbusConfig config, UpstreamClient upstream, InstantSource clock) {
        Cache<String, CacheEntry> cache = new LinkedHashMapCache<>(config.cacheMaxEntries(), config.cacheMaxBytes(),
                (key, entry) -> entry.weightBytes(key));
        ClientRateLimiter rateLimiter = new ClientRateLimiter(config.clientCapacity(), config.clientRefillPerSecond(),
                clock);
        UpstreamBudget budget = UpstreamBudget.from(config, clock);
        ProxyMetrics metrics = new ProxyMetrics(config, cache, budget, clock);
        ProxyService service = new ProxyService(config.routes(), cache, upstream, rateLimiter, budget,
                new Semaphore(config.upstreamMaxConcurrent(), true), FreshnessPolicy.from(config), metrics, clock,
                config);
        return new ProxyFixture(config, cache, rateLimiter, budget, metrics, service);
    }
}
