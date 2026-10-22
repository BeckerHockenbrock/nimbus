package dev.nimbus.proxy;

/** How a proxied request ended. Every proxied request has exactly one (PLAN.md, Section 3.6). */
public enum Outcome {
    /** Fresh cached 200; no upstream call. */
    HIT,
    /** Shared another request's upstream call (Track 1). */
    COALESCED,
    /** Stale cached 200 served because the upstream could not be called (budget, cooldown, or no permit). */
    STALE_WITHOUT_UPSTREAM,
    /** Own upstream call that returned a response: 200, or a non-429 status below 500. */
    MISS,
    /** Stale cached 200 served after the upstream call failed. */
    STALE_AFTER_UPSTREAM_FAILURE,
    /** The upstream call failed and no stale copy was usable: 502, 503, or 504. */
    UPSTREAM_ERROR,
    /** The upstream could not be called and no stale copy was usable: 503. */
    UNAVAILABLE,
    /** The client exceeded its rate limit: 429. */
    RATE_LIMITED,
    /** Bad method, path, or query: 400, 404, 405, or 414. */
    INVALID,
    /** A bug in the proxy: 500. */
    INTERNAL_ERROR;

    /** The {@code X-Cache} header value, or {@code null} for error and rejection responses. */
    public String xCacheHeader() {
        return switch (this) {
            case HIT -> "HIT";
            case MISS -> "MISS";
            case STALE_WITHOUT_UPSTREAM, STALE_AFTER_UPSTREAM_FAILURE -> "STALE";
            case COALESCED -> "COALESCED";
            case UPSTREAM_ERROR, UNAVAILABLE, RATE_LIMITED, INVALID, INTERNAL_ERROR -> null;
        };
    }
}
