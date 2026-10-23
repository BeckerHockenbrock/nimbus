package dev.nimbus.config;

import dev.nimbus.proxy.Route;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Immutable configuration. Every key and default is listed in {@code nimbus.properties} (PLAN.md, Appendix B).
 *
 * <p>Precedence, lowest to highest: classpath {@code nimbus.properties} &lt; {@code --config=<file>} &lt;
 * environment ({@code NIMBUS_CACHE_MAXBYTES}) &lt; system properties ({@code -Dnimbus.cache.maxBytes}) &lt;
 * CLI flags ({@code --nimbus.cache.maxBytes=...}; {@code --port} is a shortcut for {@code nimbus.port}).
 *
 * <p>Invalid values fail fast with an {@link IllegalArgumentException} that names the key.
 */
public record NimbusConfig(
        String host,
        int port,
        URI forecastUpstream,
        URI geocodingUpstream,
        int maxQueryLength,
        int maxQueryParams,
        String cacheImpl,
        long cacheMaxBytes,
        int cacheMaxEntries,
        long cacheMaxEntryBytes,
        Duration forecastTtl,
        Duration forecastStale,
        Duration geocodingTtl,
        Duration geocodingStale,
        int clientCapacity,
        double clientRefillPerSecond,
        Duration clientIdleEvict,
        int upstreamPerMinute,
        int upstreamPerHour,
        int upstreamPerDay,
        Duration upstreamCooldown,
        Duration upstreamConnectTimeout,
        Duration upstreamDeadline,
        long upstreamMaxResponseBytes,
        int upstreamMaxConcurrent,
        Duration upstreamPermitWait,
        int metricsLatencyWindow,
        URI guiProxyUrl,
        Duration guiMetricsRefresh) {

    static final String DEFAULTS_RESOURCE = "/nimbus.properties";
    static final Set<String> CACHE_IMPLS = Set.of("linkedhashmap", "custom");

    public NimbusConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("nimbus.host must not be blank");
        }
        requireRange("nimbus.port", port, 0, 65_535);
        requireHttpUrl("nimbus.forecast.upstream", forecastUpstream);
        requireHttpUrl("nimbus.geocoding.upstream", geocodingUpstream);
        requireRange("nimbus.request.maxQueryLength", maxQueryLength, 1, Integer.MAX_VALUE);
        requireRange("nimbus.request.maxQueryParams", maxQueryParams, 1, Integer.MAX_VALUE);
        if (!CACHE_IMPLS.contains(cacheImpl)) {
            throw new IllegalArgumentException("nimbus.cache.impl must be one of " + CACHE_IMPLS + ", got '" + cacheImpl + "'");
        }
        requireRange("nimbus.cache.maxBytes", cacheMaxBytes, 1, Long.MAX_VALUE);
        requireRange("nimbus.cache.maxEntries", cacheMaxEntries, 1, Integer.MAX_VALUE);
        requireRange("nimbus.cache.maxEntryBytes", cacheMaxEntryBytes, 1, Long.MAX_VALUE);
        requireMinimum("nimbus.cache.forecast.ttlSeconds", forecastTtl, Duration.ofSeconds(1));
        requireMinimum("nimbus.cache.forecast.staleSeconds", forecastStale, Duration.ZERO);
        requireMinimum("nimbus.cache.geocoding.ttlSeconds", geocodingTtl, Duration.ofSeconds(1));
        requireMinimum("nimbus.cache.geocoding.staleSeconds", geocodingStale, Duration.ZERO);
        requireRange("nimbus.ratelimit.client.capacity", clientCapacity, 1, Integer.MAX_VALUE);
        if (!(clientRefillPerSecond > 0) || Double.isInfinite(clientRefillPerSecond)) {
            throw new IllegalArgumentException("nimbus.ratelimit.client.refillPerSecond must be a positive number, got "
                    + clientRefillPerSecond);
        }
        requireMinimum("nimbus.ratelimit.client.idleEvictSeconds", clientIdleEvict, Duration.ofSeconds(1));
        requireRange("nimbus.upstream.perMinute", upstreamPerMinute, 1, Integer.MAX_VALUE);
        requireRange("nimbus.upstream.perHour", upstreamPerHour, 1, Integer.MAX_VALUE);
        requireRange("nimbus.upstream.perDay", upstreamPerDay, 1, Integer.MAX_VALUE);
        requireMinimum("nimbus.upstream.cooldownSeconds", upstreamCooldown, Duration.ofSeconds(1));
        requireMinimum("nimbus.upstream.connectTimeoutMs", upstreamConnectTimeout, Duration.ofMillis(1));
        requireMinimum("nimbus.upstream.deadlineMs", upstreamDeadline, Duration.ofMillis(1));
        requireRange("nimbus.upstream.maxResponseBytes", upstreamMaxResponseBytes, 1, Long.MAX_VALUE);
        requireRange("nimbus.upstream.maxConcurrent", upstreamMaxConcurrent, 1, Integer.MAX_VALUE);
        requireMinimum("nimbus.upstream.permitWaitMs", upstreamPermitWait, Duration.ZERO);
        requireRange("nimbus.metrics.latencyWindow", metricsLatencyWindow, 1, Integer.MAX_VALUE);
        requireHttpUrl("nimbus.gui.proxyUrl", guiProxyUrl);
        requireMinimum("nimbus.gui.metricsRefreshMs", guiMetricsRefresh, Duration.ofMillis(1));

        // Relationship checks (PLAN.md, Section 3.7).
        if (cacheMaxEntryBytes > upstreamMaxResponseBytes) {
            throw new IllegalArgumentException("nimbus.cache.maxEntryBytes (" + cacheMaxEntryBytes
                    + ") must be <= nimbus.upstream.maxResponseBytes (" + upstreamMaxResponseBytes + ")");
        }
        if (cacheMaxEntryBytes > cacheMaxBytes) {
            throw new IllegalArgumentException("nimbus.cache.maxEntryBytes (" + cacheMaxEntryBytes
                    + ") must be <= nimbus.cache.maxBytes (" + cacheMaxBytes + ")");
        }
    }

    /** The classpath defaults with nothing overridden. */
    public static NimbusConfig defaults() {
        return withOverrides(Map.of());
    }

    /** The classpath defaults with {@code overrides} applied on top. Used by tests. */
    public static NimbusConfig withOverrides(Map<String, String> overrides) {
        Map<String, String> values = loadDefaults();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            putKnown(values, e.getKey(), e.getValue(), "override");
        }
        return fromMap(values);
    }

    /**
     * Loads the configuration with full precedence.
     *
     * @param flags  {@code --key=value} command-line flags (the mode argument already removed)
     * @param env    environment variables, normally {@link System#getenv()}
     * @param system system properties, normally {@link System#getProperties()}
     */
    public static NimbusConfig load(List<String> flags, Map<String, String> env, Properties system) {
        Map<String, String> values = loadDefaults();

        Map<String, String> cli = new LinkedHashMap<>();
        String configFile = null;
        for (String flag : flags) {
            int eq = flag.indexOf('=');
            if (!flag.startsWith("--") || eq < 3) {
                throw new IllegalArgumentException("Expected --key=value, got: " + flag);
            }
            String key = flag.substring(2, eq);
            String value = flag.substring(eq + 1);
            switch (key) {
                case "config" -> configFile = value;
                case "port" -> cli.put("nimbus.port", value);
                default -> {
                    if (!values.containsKey(key)) {
                        throw new IllegalArgumentException("Unknown setting: --" + key);
                    }
                    cli.put(key, value);
                }
            }
        }

        if (configFile != null) {
            for (Map.Entry<String, String> e : readFile(Path.of(configFile)).entrySet()) {
                putKnown(values, e.getKey(), e.getValue(), configFile);
            }
        }
        for (String key : List.copyOf(values.keySet())) {
            String fromEnv = env.get(envName(key));
            if (fromEnv != null) {
                values.put(key, fromEnv);
            }
        }
        for (String key : List.copyOf(values.keySet())) {
            String fromSystem = system.getProperty(key);
            if (fromSystem != null) {
                values.put(key, fromSystem);
            }
        }
        values.putAll(cli);
        return fromMap(values);
    }

    /** {@code nimbus.cache.maxBytes} becomes {@code NIMBUS_CACHE_MAXBYTES}. */
    static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    static NimbusConfig fromMap(Map<String, String> v) {
        return new NimbusConfig(
                string(v, "nimbus.host"),
                integer(v, "nimbus.port"),
                uri(v, "nimbus.forecast.upstream"),
                uri(v, "nimbus.geocoding.upstream"),
                integer(v, "nimbus.request.maxQueryLength"),
                integer(v, "nimbus.request.maxQueryParams"),
                string(v, "nimbus.cache.impl"),
                longValue(v, "nimbus.cache.maxBytes"),
                integer(v, "nimbus.cache.maxEntries"),
                longValue(v, "nimbus.cache.maxEntryBytes"),
                Duration.ofSeconds(longValue(v, "nimbus.cache.forecast.ttlSeconds")),
                Duration.ofSeconds(longValue(v, "nimbus.cache.forecast.staleSeconds")),
                Duration.ofSeconds(longValue(v, "nimbus.cache.geocoding.ttlSeconds")),
                Duration.ofSeconds(longValue(v, "nimbus.cache.geocoding.staleSeconds")),
                integer(v, "nimbus.ratelimit.client.capacity"),
                doubleValue(v, "nimbus.ratelimit.client.refillPerSecond"),
                Duration.ofSeconds(longValue(v, "nimbus.ratelimit.client.idleEvictSeconds")),
                integer(v, "nimbus.upstream.perMinute"),
                integer(v, "nimbus.upstream.perHour"),
                integer(v, "nimbus.upstream.perDay"),
                Duration.ofSeconds(longValue(v, "nimbus.upstream.cooldownSeconds")),
                Duration.ofMillis(longValue(v, "nimbus.upstream.connectTimeoutMs")),
                Duration.ofMillis(longValue(v, "nimbus.upstream.deadlineMs")),
                longValue(v, "nimbus.upstream.maxResponseBytes"),
                integer(v, "nimbus.upstream.maxConcurrent"),
                Duration.ofMillis(longValue(v, "nimbus.upstream.permitWaitMs")),
                integer(v, "nimbus.metrics.latencyWindow"),
                uri(v, "nimbus.gui.proxyUrl"),
                Duration.ofMillis(longValue(v, "nimbus.gui.metricsRefreshMs")));
    }

    public Route forecastRoute() {
        return new Route(Route.FORECAST_PATH, forecastUpstream, Set.of("latitude", "longitude"),
                forecastTtl, forecastStale);
    }

    public Route geocodingRoute() {
        return new Route(Route.GEOCODING_PATH, geocodingUpstream, Set.of("name"), geocodingTtl, geocodingStale);
    }

    /** The fixed route table (PLAN.md, Section 3.3). */
    public List<Route> routes() {
        return List.of(forecastRoute(), geocodingRoute());
    }

    // --- loading ---

    static Map<String, String> loadDefaults() {
        try (InputStream in = NimbusConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + DEFAULTS_RESOURCE);
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return toMap(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DEFAULTS_RESOURCE, e);
        }
    }

    private static Map<String, String> readFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(reader);
            return toMap(p);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read config file " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> toMap(Properties p) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : Collections.list(p.propertyNames()).stream().map(String::valueOf).sorted().toList()) {
            map.put(name, p.getProperty(name));
        }
        return map;
    }

    private static void putKnown(Map<String, String> values, String key, String value, String source) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException("Unknown setting '" + key + "' in " + source);
        }
        values.put(key, Objects.requireNonNull(value, key));
    }

    // --- parsing ---

    private static String string(Map<String, String> v, String key) {
        String value = v.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing setting " + key);
        }
        return value.trim();
    }

    private static int integer(Map<String, String> v, String key) {
        String value = string(v, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'", e);
        }
    }

    private static long longValue(Map<String, String> v, String key) {
        String value = string(v, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + value + "'", e);
        }
    }

    private static double doubleValue(Map<String, String> v, String key) {
        String value = string(v, key);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'", e);
        }
    }

    private static URI uri(Map<String, String> v, String key) {
        String value = string(v, key);
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(key + " must be a URL, got '" + value + "'", e);
        }
    }

    // --- validation ---

    private static void requireRange(String key, long value, long min, long max) {
        if (value < min || value > max) {
            String range = max == Long.MAX_VALUE || max == Integer.MAX_VALUE ? ">= " + min : min + ".." + max;
            throw new IllegalArgumentException(key + " must be " + range + ", got " + value);
        }
    }

    private static void requireMinimum(String key, Duration value, Duration min) {
        if (value.compareTo(min) < 0) {
            boolean millis = key.endsWith("Ms");
            long got = millis ? value.toMillis() : value.toSeconds();
            long least = millis ? min.toMillis() : min.toSeconds();
            throw new IllegalArgumentException(key + " must be >= " + least + ", got " + got);
        }
    }

    private static void requireHttpUrl(String key, URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException(key + " must be an absolute http(s) URL, got '" + uri + "'");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(key + " must not contain a query or fragment, got '" + uri + "'");
        }
    }
}
