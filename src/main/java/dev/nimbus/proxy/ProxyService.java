package dev.nimbus.proxy;

import dev.nimbus.cache.Cache;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.upstream.UpstreamClient;
import dev.nimbus.upstream.UpstreamException;
import dev.nimbus.upstream.UpstreamResponse;

import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every proxy decision (PLAN.md, Section 3.2). Plain Java: takes a {@link ProxyRequest}, returns a
 * {@link ProxyResponse}, and never touches sockets.
 */
public final class ProxyService {

    private static final System.Logger LOG = System.getLogger(ProxyService.class.getName());

    private final Map<String, Route> routes;
    private final Cache<String, CacheEntry> cache;
    private final UpstreamClient upstream;
    private final FreshnessPolicy freshness;
    private final InstantSource clock;
    private final NimbusConfig config;
    private final QueryNormalizer normalizer;

    public ProxyService(List<Route> routes, Cache<String, CacheEntry> cache, UpstreamClient upstream,
                        FreshnessPolicy freshness, InstantSource clock, NimbusConfig config) {
        this.routes = routes.stream().collect(Collectors.toUnmodifiableMap(Route::path, Function.identity()));
        this.cache = cache;
        this.upstream = upstream;
        this.freshness = freshness;
        this.clock = clock;
        this.config = config;
        this.normalizer = new QueryNormalizer(config.maxQueryLength(), config.maxQueryParams());
    }

    public ProxyResponse handle(ProxyRequest request) {
        if (!"GET".equals(request.method())) {
            return ProxyResponse.error(405, "Method " + request.method() + " is not allowed; use GET",
                    Outcome.INVALID, Map.of("Allow", "GET"));
        }
        Route route = routes.get(request.path());
        if (route == null) {
            return ProxyResponse.error(404, "Unknown path: " + request.path(), Outcome.INVALID);
        }
        String key;
        try {
            key = route.cacheKey(normalizer.canonicalize(request.rawQuery(), route));
        } catch (BadRequestException e) {
            return ProxyResponse.error(e.status(), e.reason(), Outcome.INVALID);
        }

        Instant now = clock.instant();
        CacheEntry entry = cache.get(key);
        if (entry != null && !now.isBefore(entry.staleUntil())) {
            // Past any use. A concurrent request may have just stored a newer entry; removing it is still safe.
            cache.remove(key);
            entry = null;
        }
        if (entry != null && entry.isFresh(now)) {
            return hit(entry, now);
        }
        // An expired entry still inside its stale window stays cached; stale-if-error uses it from Phase 5.
        return fetchFromUpstream(route, key);
    }

    /** A fresh cached 200. {@code Cache-Control} tells clients how long it stays fresh. */
    private static ProxyResponse hit(CacheEntry entry, Instant now) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (entry.contentType() != null) {
            headers.put("Content-Type", entry.contentType());
        }
        headers.put("X-Cache", Outcome.HIT.xCacheHeader());
        headers.put("Age", Long.toString(entry.ageSeconds(now)));
        headers.put("Cache-Control", "max-age=" + entry.freshSecondsRemaining(now));
        return new ProxyResponse(200, headers, entry.body(), Outcome.HIT);
    }

    /** The single place where the upstream is called. */
    private ProxyResponse fetchFromUpstream(Route route, String key) {
        UpstreamResponse response;
        try {
            response = upstream.fetch(route.upstreamUri(key));
        } catch (UpstreamException e) {
            LOG.log(System.Logger.Level.WARNING, "Upstream " + e.kind() + " for " + key + ": " + e.getMessage());
            return upstreamFailure(e);
        }
        int status = response.status();
        if (status == 429) {
            long retryAfter = Math.max(config.upstreamCooldown().toSeconds(), retryAfterSeconds(response));
            return ProxyResponse.error(503, "Upstream rate limit reached", Outcome.UPSTREAM_ERROR,
                    Map.of("Retry-After", Long.toString(Math.max(1, retryAfter))));
        }
        if (status >= 500) {
            return ProxyResponse.error(502, "Upstream returned HTTP " + status, Outcome.UPSTREAM_ERROR);
        }
        if (status == 200) {
            store(route, key, response);
        }
        // Other statuses below 500 are relayed and leave any existing entry alone.
        return relay(response);
    }

    /**
     * Store-or-remove: after an upstream 200 the cache holds that response or nothing, so an older body is never
     * served after a newer one was seen.
     */
    private void store(Route route, String key, UpstreamResponse response) {
        FreshnessPolicy.Decision decision = freshness.decide(route, response, clock.instant());
        switch (decision) {
            case FreshnessPolicy.Store store -> cache.put(key, store.entry());
            case FreshnessPolicy.DoNotStore doNotStore -> {
                cache.remove(key);
                LOG.log(System.Logger.Level.DEBUG, "Not caching " + key + ": " + doNotStore.reason());
            }
        }
    }

    /** A response received from the upstream, relayed as-is (PLAN.md, E4). */
    private static ProxyResponse relay(UpstreamResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        String contentType = response.contentType();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        List<String> cacheControl = response.headers().allValues("Cache-Control");
        if (!cacheControl.isEmpty()) {
            headers.put("Cache-Control", String.join(", ", cacheControl));
        }
        headers.put("X-Cache", Outcome.MISS.xCacheHeader());
        headers.put("X-Upstream-Latency-Ms", millis(response.latencyNanos()));
        return new ProxyResponse(response.status(), headers, response.body(), Outcome.MISS);
    }

    private ProxyResponse upstreamFailure(UpstreamException e) {
        return switch (e.kind()) {
            case TIMEOUT -> ProxyResponse.error(504,
                    "Upstream did not respond within " + config.upstreamDeadline().toMillis() + " ms",
                    Outcome.UPSTREAM_ERROR);
            case TOO_LARGE -> ProxyResponse.error(502,
                    "Upstream response exceeds " + config.upstreamMaxResponseBytes() + " bytes",
                    Outcome.UPSTREAM_ERROR);
            case CONNECTION -> ProxyResponse.error(502, "Cannot reach upstream", Outcome.UPSTREAM_ERROR);
            case INTERRUPTED -> ProxyResponse.error(502, "Interrupted while calling upstream", Outcome.UPSTREAM_ERROR);
        };
    }

    /** The upstream {@code Retry-After} in delta-seconds, or 0 if absent or not a number. */
    private static long retryAfterSeconds(UpstreamResponse response) {
        String value = response.firstHeader("Retry-After");
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 0; // An HTTP-date; the configured cooldown applies.
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
