package dev.nimbus.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * One proxied endpoint: requests to {@code path} are forwarded to {@code upstreamUrl}.
 *
 * @param path           exact request path, such as {@code /v1/forecast}
 * @param upstreamUrl    absolute upstream URL without a query
 * @param requiredParams parameter names that must appear at least once with a value
 * @param ttl            configured freshness lifetime (upstream headers can only shorten it)
 * @param staleWindow    how long after expiry an entry may be served on upstream failure
 */
public record Route(String path, URI upstreamUrl, Set<String> requiredParams, Duration ttl, Duration staleWindow) {

    public static final String FORECAST_PATH = "/v1/forecast";
    public static final String GEOCODING_PATH = "/v1/search";

    public Route {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(upstreamUrl, "upstreamUrl");
        requiredParams = Set.copyOf(requiredParams);
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(staleWindow, "staleWindow");
    }

    /** The cache key for a canonical query: {@code path + "?" + canonicalQuery}. */
    public String cacheKey(String canonicalQuery) {
        return canonicalQuery.isEmpty() ? path : path + "?" + canonicalQuery;
    }

    /** The upstream URL for a cache key of this route, which carries the same canonical query. */
    public URI upstreamUri(String cacheKey) {
        return URI.create(upstreamUrl + cacheKey.substring(path.length()));
    }
}
