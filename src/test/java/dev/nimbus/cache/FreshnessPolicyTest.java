package dev.nimbus.cache;

import static dev.nimbus.testutil.TestEntries.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.config.NimbusConfig;
import dev.nimbus.proxy.Route;
import dev.nimbus.testutil.MutableClock;
import dev.nimbus.upstream.UpstreamResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FreshnessPolicyTest {

    private static final String BODY = "{\"current\":{}}";

    private final NimbusConfig config = NimbusConfig.defaults();
    private final Route forecast = config.forecastRoute();
    private final Route geocoding = config.geocodingRoute();
    private final FreshnessPolicy policy = FreshnessPolicy.from(config);
    private final MutableClock clock = new MutableClock();

    private static String httpDate(Instant instant) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
    }

    private CacheEntry stored(Route route, UpstreamResponse response) {
        FreshnessPolicy.Decision decision = policy.decide(route, response, clock.instant());
        return assertInstanceOf(FreshnessPolicy.Store.class, decision, decision.toString()).entry();
    }

    private void notStored(Route route, UpstreamResponse response) {
        FreshnessPolicy.Decision decision = policy.decide(route, response, clock.instant());
        assertInstanceOf(FreshnessPolicy.DoNotStore.class, decision, decision.toString());
    }

    private long freshSeconds(CacheEntry entry) {
        return Duration.between(clock.instant(), entry.freshUntil()).toSeconds();
    }

    @Test
    void noHeadersUseTheConfiguredTtl() {
        CacheEntry entry = stored(forecast, response(200, BODY, "Content-Type", "application/json"));

        assertEquals(300, freshSeconds(entry));
        assertTrue(entry.staleAllowed());
        assertEquals(entry.freshUntil().plusSeconds(3600), entry.staleUntil());
        assertEquals(clock.instant(), entry.receivedAt());
        assertEquals(0, entry.upstreamAgeSeconds());
        assertEquals("application/json", entry.contentType());
        assertNull(entry.upstreamCacheControl());
    }

    @Test
    void bodyIsStoredWithoutCopying() {
        UpstreamResponse response = response(200, BODY);
        assertSame(response.body(), stored(forecast, response).body());
    }

    @Test
    void maxAgeCanOnlyShortenTheTtl() {
        assertEquals(60, freshSeconds(stored(forecast, response(200, BODY, "Cache-Control", "max-age=60"))));
        assertEquals(300, freshSeconds(stored(forecast, response(200, BODY, "Cache-Control", "max-age=999999"))));
    }

    @Test
    void sMaxAgeTakesPrecedenceOverMaxAge() {
        assertEquals(30, freshSeconds(stored(forecast,
                response(200, BODY, "Cache-Control", "s-maxage=30, max-age=200"))));
        assertEquals(250, freshSeconds(stored(forecast,
                response(200, BODY, "Cache-Control", "s-maxage=250, max-age=200"))), "s-maxage wins even when longer");
    }

    @Test
    void sMaxAgeImpliesProxyRevalidate() {
        CacheEntry entry = stored(forecast, response(200, BODY, "Cache-Control", "s-maxage=30"));
        assertFalse(entry.staleAllowed());
        assertEquals(entry.freshUntil(), entry.staleUntil());
    }

    @Test
    void upstreamAgeIsSubtracted() {
        CacheEntry entry = stored(forecast, response(200, BODY, "Cache-Control", "max-age=60", "Age", "50"));
        assertEquals(10, freshSeconds(entry));
        assertEquals(50, entry.upstreamAgeSeconds());

        notStored(forecast, response(200, BODY, "Cache-Control", "max-age=60", "Age", "70"));
        notStored(forecast, response(200, BODY, "Cache-Control", "max-age=60", "Age", "60"));
        assertEquals(250, freshSeconds(stored(forecast, response(200, BODY, "Age", "50"))));
    }

    @Test
    void invalidAgeIsZero() {
        CacheEntry entry = stored(forecast, response(200, BODY, "Age", "soon"));
        assertEquals(300, freshSeconds(entry));
        assertEquals(0, entry.upstreamAgeSeconds());
    }

    @Test
    void expiresMinusDate() {
        Instant date = clock.instant().minusSeconds(10); // A server clock that differs from ours.
        CacheEntry entry = stored(forecast, response(200, BODY,
                "Date", httpDate(date), "Expires", httpDate(date.plusSeconds(120))));
        assertEquals(120, freshSeconds(entry));
    }

    @Test
    void expiresWithoutDateUsesTheReceiveTime() {
        assertEquals(90, freshSeconds(stored(forecast,
                response(200, BODY, "Expires", httpDate(clock.instant().plusSeconds(90))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "never", "Thu, 01 Jan 1970 00:00:00 GMT"})
    void invalidOrPastExpiresIsNotStored(String expires) {
        notStored(forecast, response(200, BODY, "Expires", expires));
    }

    @Test
    void maxAgeOverridesExpires() {
        assertEquals(60, freshSeconds(stored(forecast, response(200, BODY,
                "Cache-Control", "max-age=60", "Expires", httpDate(clock.instant().plusSeconds(200))))));
        assertEquals(60, freshSeconds(stored(forecast, response(200, BODY,
                "Cache-Control", "max-age=60", "Expires", "0"))), "even an invalid Expires");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-store", "private", "no-cache", "public, no-store", "private=\"Set-Cookie\""})
    void forbiddingDirectivesAreNotStored(String cacheControl) {
        notStored(forecast, response(200, BODY, "Cache-Control", cacheControl));
    }

    @Test
    void varyStarIsNotStored() {
        notStored(forecast, response(200, BODY, "Vary", "*"));
        notStored(forecast, response(200, BODY, "Vary", "Accept, *"));
        notStored(forecast, response(200, BODY, "Vary", "Accept", "Vary", " * "));
        stored(forecast, response(200, BODY, "Vary", "Accept-Encoding"));
    }

    @Test
    void revalidateDirectivesForbidStale() {
        for (String cc : new String[] {"must-revalidate", "proxy-revalidate"}) {
            CacheEntry entry = stored(forecast, response(200, BODY, "Cache-Control", cc));
            assertFalse(entry.staleAllowed(), cc);
            assertEquals(entry.freshUntil(), entry.staleUntil(), cc);
        }
    }

    @Test
    void zeroStaleWindowForbidsStale() {
        NimbusConfig noStale = NimbusConfig.withOverrides(Map.of("nimbus.cache.forecast.staleSeconds", "0"));
        CacheEntry entry = stored(noStale.forecastRoute(), response(200, BODY));
        assertFalse(entry.staleAllowed());
        assertEquals(entry.freshUntil(), entry.staleUntil());
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 203, 204, 301, 400, 404, 429, 500, 503})
    void non200IsNotStored(int status) {
        notStored(forecast, response(status, BODY));
    }

    @Test
    void bodyOverMaxEntryBytesIsNotStored() {
        int max = (int) config.cacheMaxEntryBytes();
        notStored(forecast, response(200, new byte[max + 1]));
        assertEquals(max, stored(forecast, response(200, new byte[max])).body().length);
    }

    @Test
    void geocodingHeadersFromTheRealApi() {
        CacheEntry entry = stored(geocoding, response(200, BODY, "Cache-Control", "public, max-age=86400"));
        assertEquals(86_400, freshSeconds(entry));
        assertTrue(entry.staleAllowed());
        assertEquals(entry.freshUntil().plusSeconds(604_800), entry.staleUntil());
        assertEquals("public, max-age=86400", entry.upstreamCacheControl());
    }

    @Test
    void multipleCacheControlLinesAreJoinedForTheEntry() {
        CacheEntry entry = stored(geocoding, response(200, BODY, "Cache-Control", "public", "Cache-Control", "max-age=60"));
        assertEquals("public, max-age=60", entry.upstreamCacheControl());
        assertEquals(60, freshSeconds(entry));
    }
}
