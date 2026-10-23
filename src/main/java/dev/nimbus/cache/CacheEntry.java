package dev.nimbus.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A cached upstream 200 (PLAN.md, Section 3.5).
 *
 * <p>{@code body} is the array received from the upstream, stored and served without copying. It must be treated as
 * immutable.
 *
 * @param contentType          upstream {@code Content-Type}, or {@code null}
 * @param upstreamCacheControl upstream {@code Cache-Control} lines joined with {@code ", "}, or {@code null}
 * @param upstreamAgeSeconds   the upstream {@code Age} header when received (0 if absent)
 * @param staleAllowed         whether the entry may be served stale on upstream failure
 */
public record CacheEntry(byte[] body, String contentType, String upstreamCacheControl,
                         Instant receivedAt, long upstreamAgeSeconds,
                         Instant freshUntil, Instant staleUntil, boolean staleAllowed) {

    /**
     * Estimated per-entry overhead (object headers, {@code Instant}s, strings, map nodes). Approximate; benchmark B6
     * checks it against real heap usage.
     */
    public static final long ENTRY_OVERHEAD_BYTES = 256;

    public CacheEntry {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        Objects.requireNonNull(staleUntil, "staleUntil");
        if (staleUntil.isBefore(freshUntil)) {
            throw new IllegalArgumentException("staleUntil is before freshUntil");
        }
    }

    public boolean isFresh(Instant now) {
        return now.isBefore(freshUntil);
    }

    public boolean isStaleUsable(Instant now) {
        return staleAllowed && now.isBefore(staleUntil);
    }

    /** The {@code Age} to report: the upstream age plus whole seconds since the entry was received. */
    public long ageSeconds(Instant now) {
        return upstreamAgeSeconds + Math.max(0, Duration.between(receivedAt, now).toSeconds());
    }

    /** Whole seconds until {@code freshUntil}, rounded down, never negative. */
    public long freshSecondsRemaining(Instant now) {
        return Math.max(0, Duration.between(now, freshUntil).toSeconds());
    }

    /** The cache weight of this entry under {@code key}: body + key (2 bytes per char) + overhead. */
    public long weightBytes(String key) {
        return body.length + 2L * key.length() + ENTRY_OVERHEAD_BYTES;
    }
}
