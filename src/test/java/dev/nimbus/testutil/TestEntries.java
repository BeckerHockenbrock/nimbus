package dev.nimbus.testutil;

import dev.nimbus.cache.CacheEntry;
import dev.nimbus.upstream.UpstreamResponse;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builders for {@link UpstreamResponse} and {@link CacheEntry}. */
public final class TestEntries {

    /** The latency every built response reports: 1 ms. */
    public static final long LATENCY_NANOS = 1_000_000L;

    private TestEntries() {
    }

    /** Builds a response; {@code headerNameValues} alternates names and values, and names may repeat. */
    public static UpstreamResponse response(int status, byte[] body, String... headerNameValues) {
        if (headerNameValues.length % 2 != 0) {
            throw new IllegalArgumentException("header names and values must come in pairs");
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerNameValues.length; i += 2) {
            headers.computeIfAbsent(headerNameValues[i], k -> new ArrayList<>()).add(headerNameValues[i + 1]);
        }
        return new UpstreamResponse(status, body, HttpHeaders.of(headers, (name, value) -> true), LATENCY_NANOS);
    }

    public static UpstreamResponse response(int status, String body, String... headerNameValues) {
        return response(status, body.getBytes(StandardCharsets.UTF_8), headerNameValues);
    }

    /** A JSON entry received at {@code receivedAt}, fresh for {@code fresh}, then usable stale for {@code stale}. */
    public static CacheEntry entry(String body, Instant receivedAt, Duration fresh, Duration stale) {
        Instant freshUntil = receivedAt.plus(fresh);
        return new CacheEntry(body.getBytes(StandardCharsets.UTF_8), "application/json", null, receivedAt, 0,
                freshUntil, freshUntil.plus(stale), stale.isPositive());
    }
}
