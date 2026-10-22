package dev.nimbus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nimbus.proxy.Route;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NimbusConfigTest {

    @TempDir
    Path tempDir;

    private static NimbusConfig load(List<String> flags, Map<String, String> env, Properties system) {
        return NimbusConfig.load(flags, env, system);
    }

    @Test
    void defaultsMatchAppendixB() {
        NimbusConfig c = NimbusConfig.defaults();
        assertEquals("127.0.0.1", c.host());
        assertEquals(8080, c.port());
        assertEquals(URI.create("https://api.open-meteo.com/v1/forecast"), c.forecastUpstream());
        assertEquals(URI.create("https://geocoding-api.open-meteo.com/v1/search"), c.geocodingUpstream());
        assertEquals(4096, c.maxQueryLength());
        assertEquals(100, c.maxQueryParams());
        assertEquals("linkedhashmap", c.cacheImpl());
        assertEquals(64L * 1024 * 1024, c.cacheMaxBytes());
        assertEquals(10_000, c.cacheMaxEntries());
        assertEquals(512L * 1024, c.cacheMaxEntryBytes());
        assertEquals(Duration.ofMinutes(5), c.forecastTtl());
        assertEquals(Duration.ofHours(1), c.forecastStale());
        assertEquals(Duration.ofDays(1), c.geocodingTtl());
        assertEquals(Duration.ofDays(7), c.geocodingStale());
        assertEquals(30, c.clientCapacity());
        assertEquals(10.0, c.clientRefillPerSecond());
        assertEquals(Duration.ofMinutes(10), c.clientIdleEvict());
        assertEquals(480, c.upstreamPerMinute());
        assertEquals(4000, c.upstreamPerHour());
        assertEquals(8000, c.upstreamPerDay());
        assertEquals(Duration.ofSeconds(60), c.upstreamCooldown());
        assertEquals(Duration.ofSeconds(3), c.upstreamConnectTimeout());
        assertEquals(Duration.ofSeconds(5), c.upstreamDeadline());
        assertEquals(2L * 1024 * 1024, c.upstreamMaxResponseBytes());
        assertEquals(32, c.upstreamMaxConcurrent());
        assertEquals(Duration.ofSeconds(1), c.upstreamPermitWait());
        assertEquals(4096, c.metricsLatencyWindow());
        assertEquals(URI.create("http://127.0.0.1:8080"), c.guiProxyUrl());
        assertEquals(Duration.ofSeconds(1), c.guiMetricsRefresh());
    }

    @Test
    void routesFollowTheContract() {
        NimbusConfig c = NimbusConfig.defaults();
        Route forecast = c.forecastRoute();
        assertEquals("/v1/forecast", forecast.path());
        assertEquals(Set.of("latitude", "longitude"), forecast.requiredParams());
        assertEquals(Duration.ofSeconds(300), forecast.ttl());
        assertEquals(Duration.ofSeconds(3600), forecast.staleWindow());
        Route search = c.geocodingRoute();
        assertEquals("/v1/search", search.path());
        assertEquals(Set.of("name"), search.requiredParams());
        assertEquals(Duration.ofSeconds(86_400), search.ttl());
        assertEquals(Duration.ofSeconds(604_800), search.staleWindow());
        assertEquals(List.of(forecast, search), c.routes());
    }

    @Test
    void precedenceIsDefaultsFileEnvSystemCli() throws IOException {
        Path file = tempDir.resolve("nimbus.properties");
        Files.writeString(file, """
                nimbus.cache.maxEntries=11
                nimbus.cache.maxBytes=1000000
                nimbus.upstream.perMinute=12
                nimbus.upstream.perHour=13
                nimbus.upstream.perDay=14
                """);
        Map<String, String> env = Map.of(
                "NIMBUS_CACHE_MAXBYTES", "2000000",
                "NIMBUS_UPSTREAM_PERMINUTE", "22",
                "NIMBUS_UPSTREAM_PERHOUR", "23",
                "UNRELATED", "ignored");
        Properties system = new Properties();
        system.setProperty("nimbus.upstream.perMinute", "32");
        system.setProperty("nimbus.upstream.perHour", "33");
        List<String> flags = List.of("--config=" + file, "--nimbus.upstream.perHour=43");

        NimbusConfig c = load(flags, env, system);

        assertEquals(4096, c.maxQueryLength(), "untouched default");
        assertEquals(11, c.cacheMaxEntries(), "file over default");
        assertEquals(14, c.upstreamPerDay(), "file over default");
        assertEquals(2_000_000, c.cacheMaxBytes(), "env over file");
        assertEquals(32, c.upstreamPerMinute(), "system property over env");
        assertEquals(43, c.upstreamPerHour(), "CLI over system property");
    }

    @Test
    void portShortcutAndFullKey() {
        assertEquals(9000, load(List.of("--port=9000"), Map.of(), new Properties()).port());
        assertEquals(0, load(List.of("--nimbus.port=0"), Map.of(), new Properties()).port());
    }

    @Test
    void envNameIsUppercaseWithUnderscores() {
        assertEquals("NIMBUS_CACHE_MAXBYTES", NimbusConfig.envName("nimbus.cache.maxBytes"));
    }

    @Test
    void unknownSettingsAreRejected() throws IOException {
        IllegalArgumentException cli = assertThrows(IllegalArgumentException.class,
                () -> load(List.of("--nimbus.cache.maxbytes=1"), Map.of(), new Properties()));
        assertTrue(cli.getMessage().contains("nimbus.cache.maxbytes"), cli.getMessage());

        Path file = tempDir.resolve("typo.properties");
        Files.writeString(file, "nimbus.cahce.maxBytes=1\n");
        assertThrows(IllegalArgumentException.class,
                () -> load(List.of("--config=" + file), Map.of(), new Properties()));

        assertThrows(IllegalArgumentException.class, () -> NimbusConfig.withOverrides(Map.of("nope", "1")));
        assertThrows(IllegalArgumentException.class,
                () -> load(List.of("--config=" + tempDir.resolve("missing.properties")), Map.of(), new Properties()));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            "nimbus.port | 70000",
            "nimbus.port | -1",
            "nimbus.port | eighty",
            "nimbus.cache.maxBytes | 0",
            "nimbus.cache.maxEntries | 0",
            "nimbus.cache.impl | lru",
            "nimbus.cache.forecast.ttlSeconds | 0",
            "nimbus.cache.forecast.staleSeconds | -1",
            "nimbus.ratelimit.client.refillPerSecond | 0",
            "nimbus.ratelimit.client.refillPerSecond | NaN",
            "nimbus.upstream.deadlineMs | 0",
            "nimbus.upstream.permitWaitMs | -1",
            "nimbus.forecast.upstream | ftp://example.com/v1/forecast",
            "nimbus.forecast.upstream | /v1/forecast",
            "nimbus.geocoding.upstream | http://127.0.0.1:9090/v1/search?x=1",
            "nimbus.host | ' '",
    })
    void invalidValuesFailWithTheKeyName(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NimbusConfig.withOverrides(Map.of(key, value)));
        assertTrue(e.getMessage().contains(key), e.getMessage());
    }

    @Test
    void staleWindowZeroIsAllowed() {
        assertEquals(Duration.ZERO,
                NimbusConfig.withOverrides(Map.of("nimbus.cache.forecast.staleSeconds", "0")).forecastStale());
    }

    @Test
    void maxEntryBytesMustFitResponseLimitAndCache() {
        IllegalArgumentException overResponse = assertThrows(IllegalArgumentException.class,
                () -> NimbusConfig.withOverrides(Map.of(
                        "nimbus.cache.maxEntryBytes", "3000000",
                        "nimbus.upstream.maxResponseBytes", "2000000")));
        assertTrue(overResponse.getMessage().contains("nimbus.upstream.maxResponseBytes"), overResponse.getMessage());

        IllegalArgumentException overCache = assertThrows(IllegalArgumentException.class,
                () -> NimbusConfig.withOverrides(Map.of(
                        "nimbus.cache.maxEntryBytes", "1000",
                        "nimbus.cache.maxBytes", "999")));
        assertTrue(overCache.getMessage().contains("nimbus.cache.maxBytes"), overCache.getMessage());

        NimbusConfig equal = NimbusConfig.withOverrides(Map.of(
                "nimbus.cache.maxEntryBytes", "1000",
                "nimbus.cache.maxBytes", "1000",
                "nimbus.upstream.maxResponseBytes", "1000"));
        assertEquals(1000, equal.cacheMaxEntryBytes());
    }

    @Test
    void malformedFlagsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> load(List.of("--port"), Map.of(), new Properties()));
        assertThrows(IllegalArgumentException.class, () -> load(List.of("port=1"), Map.of(), new Properties()));
    }
}
