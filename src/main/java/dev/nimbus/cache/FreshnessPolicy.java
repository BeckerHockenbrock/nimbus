package dev.nimbus.cache;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Route;
import dev.nimbus.upstream.UpstreamResponse;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Decides whether an upstream response may be stored and for how long (PLAN.md, Section 3.5; RFC 9111).
 *
 * <p>The freshness lifetime is the route's configured TTL, which the upstream can only shorten: with
 * {@code s-maxage}, else {@code max-age}, else {@code Expires − Date}. The upstream {@code Age} is subtracted.
 */
public final class FreshnessPolicy {

    public sealed interface Decision permits Store, DoNotStore {
    }

    public record Store(CacheEntry entry) implements Decision {
    }

    public record DoNotStore(String reason) implements Decision {
    }

    private final long maxEntryBytes;

    public FreshnessPolicy(long maxEntryBytes) {
        if (maxEntryBytes <= 0) {
            throw new IllegalArgumentException("maxEntryBytes must be > 0");
        }
        this.maxEntryBytes = maxEntryBytes;
    }

    public static FreshnessPolicy from(NimbusConfig config) {
        return new FreshnessPolicy(config.cacheMaxEntryBytes());
    }

    /**
     * @param now the time the response was received
     */
    public Decision decide(Route route, UpstreamResponse response, Instant now) {
        // 1. Only 200s are stored. They are heuristically cacheable (Section 4.2.2), which the configured TTL is.
        if (response.status() != 200) {
            return new DoNotStore("status " + response.status());
        }
        HttpHeaders headers = response.headers();
        List<String> cacheControlLines = headers.allValues("Cache-Control");
        CacheControl cc = CacheControl.parse(cacheControlLines);
        // 2. Section 5.2.2.5, 5.2.2.7 (shared caches), 5.2.2.4 (revalidation isn't implemented).
        if (cc.noStore()) {
            return new DoNotStore("no-store");
        }
        if (cc.isPrivate()) {
            return new DoNotStore("private");
        }
        if (cc.noCache()) {
            return new DoNotStore("no-cache");
        }
        // 3. Vary: * never matches (Section 4.1).
        for (String line : headers.allValues("Vary")) {
            for (String field : line.split(",")) {
                if (field.trim().equals("*")) {
                    return new DoNotStore("Vary: *");
                }
            }
        }
        // 4. Per-entry storage cap.
        if (response.body().length > maxEntryBytes) {
            return new DoNotStore("body of " + response.body().length + " bytes exceeds maxEntryBytes " + maxEntryBytes);
        }
        // 5. Freshness lifetime: the upstream can only shorten the configured TTL.
        long ttl = route.ttl().toSeconds();
        OptionalLong upstreamLimit = cc.sMaxAge().isPresent() ? cc.sMaxAge()
                : cc.maxAge().isPresent() ? cc.maxAge()
                : expiresLimit(headers, now);
        long lifetime = upstreamLimit.isPresent() ? Math.min(ttl, upstreamLimit.getAsLong()) : ttl;
        // 6. Simplified current age (Section 4.2.3), ignoring network delay.
        long age = CacheControl.deltaSeconds(headers.firstValue("Age").map(String::trim).orElse(null));
        long remaining = lifetime - age;
        if (remaining <= 0) {
            return new DoNotStore("already stale: lifetime " + lifetime + " s, age " + age + " s");
        }
        // 7. Timestamps. must-revalidate / proxy-revalidate forbid serving stale (Sections 5.2.2.2 and 5.2.2.8), and
        //    s-maxage carries proxy-revalidate's semantics for a shared cache (Section 5.2.2.10).
        Instant freshUntil = now.plusSeconds(remaining);
        boolean revalidate = cc.mustRevalidate() || cc.proxyRevalidate() || cc.sMaxAge().isPresent();
        boolean staleAllowed = !revalidate && route.staleWindow().isPositive();
        Instant staleUntil = staleAllowed ? freshUntil.plus(route.staleWindow()) : freshUntil;
        String upstreamCacheControl = cacheControlLines.isEmpty() ? null : String.join(", ", cacheControlLines);
        return new Store(new CacheEntry(response.body(), response.contentType(), upstreamCacheControl,
                now, age, freshUntil, staleUntil, staleAllowed));
    }

    /**
     * {@code Expires − Date} in seconds (Section 5.3), or empty without {@code Expires}. An invalid {@code Expires}
     * (such as {@code 0}) means the past, so 0. Without a valid {@code Date}, the receive time is used.
     */
    private static OptionalLong expiresLimit(HttpHeaders headers, Instant now) {
        Optional<String> expires = headers.firstValue("Expires");
        if (expires.isEmpty()) {
            return OptionalLong.empty();
        }
        Instant expiresAt = parseHttpDate(expires.get());
        if (expiresAt == null) {
            return OptionalLong.of(0);
        }
        Instant date = headers.firstValue("Date").map(FreshnessPolicy::parseHttpDate).orElse(null);
        long seconds = Duration.between(date == null ? now : date, expiresAt).toSeconds();
        return OptionalLong.of(Math.max(0, seconds));
    }

    /**
     * Parses an IMF-fixdate such as {@code Thu, 22 Oct 2026 19:45:00 GMT}, or returns {@code null}. The obsolete
     * RFC 850 and asctime forms are treated as invalid, which errs toward not caching.
     */
    static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
