package dev.nimbus.bench;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A loopback-only imitation of both Open-Meteo routes ({@code /v1/forecast} and {@code /v1/search}), used by tests
 * and benchmarks so that no load ever reaches the real API.
 *
 * <p>Every 200 body includes a {@code "fake"} object with the raw query and a request sequence number, so callers can
 * check which request produced a body. {@code GET /__stats} returns the request counts.
 */
public final class FakeUpstreamServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 9090;
    static final long TRICKLE_INTERVAL_MS = 100;

    public enum Mode {
        /** Realistic 200 responses (subject to {@code errorRate}). */
        NORMAL,
        /** Always HTTP 500. */
        ERROR500,
        /** Always HTTP 429, like Open-Meteo over its limits. */
        STATUS429,
        /** Accepts the request and never responds. */
        HANG,
        /** Sends 200 headers, then the body one byte every {@value #TRICKLE_INTERVAL_MS} ms. */
        TRICKLE;

        static Mode parse(String value) {
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown fake upstream mode: " + value);
        }
    }

    /**
     * Behavior options; they can be changed while the server runs.
     *
     * @param latencyMs    delay before responding
     * @param jitterMs     extra random delay in {@code [0, jitterMs]}
     * @param errorRate    in {@code NORMAL} mode, the probability of an HTTP 500
     * @param bodyBytes    pad 200 bodies to at least this many bytes (0 = no padding)
     * @param cacheControl {@code Cache-Control} value for 200 responses, or {@code null} for none
     * @param chunked      omit {@code Content-Length} (chunked encoding), like the real forecast endpoint
     */
    public record Options(Mode mode, long latencyMs, long jitterMs, double errorRate, int bodyBytes,
                          String cacheControl, boolean chunked) {

        public Options {
            if (latencyMs < 0 || jitterMs < 0 || bodyBytes < 0 || errorRate < 0 || errorRate > 1) {
                throw new IllegalArgumentException("Invalid fake upstream options");
            }
        }

        public static Options defaults() {
            return new Options(Mode.NORMAL, 0, 0, 0, 0, null, false);
        }

        public Options withMode(Mode m) {
            return new Options(m, latencyMs, jitterMs, errorRate, bodyBytes, cacheControl, chunked);
        }

        public Options withLatencyMs(long v) {
            return new Options(mode, v, jitterMs, errorRate, bodyBytes, cacheControl, chunked);
        }

        public Options withJitterMs(long v) {
            return new Options(mode, latencyMs, v, errorRate, bodyBytes, cacheControl, chunked);
        }

        public Options withErrorRate(double v) {
            return new Options(mode, latencyMs, jitterMs, v, bodyBytes, cacheControl, chunked);
        }

        public Options withBodyBytes(int v) {
            return new Options(mode, latencyMs, jitterMs, errorRate, v, cacheControl, chunked);
        }

        public Options withCacheControl(String v) {
            return new Options(mode, latencyMs, jitterMs, errorRate, bodyBytes, v, chunked);
        }

        public Options withChunked(boolean v) {
            return new Options(mode, latencyMs, jitterMs, errorRate, bodyBytes, cacheControl, v);
        }

        /** Parses {@code mode}, {@code latencyMs}, {@code jitterMs}, {@code errorRate}, {@code bodyBytes}, {@code cacheControl}, {@code chunked}. */
        public static Options fromFlags(Map<String, String> flags) {
            Options o = defaults();
            for (Map.Entry<String, String> e : flags.entrySet()) {
                String v = e.getValue();
                try {
                    o = switch (e.getKey()) {
                        case "mode" -> o.withMode(Mode.parse(v));
                        case "latencyMs" -> o.withLatencyMs(Long.parseLong(v));
                        case "jitterMs" -> o.withJitterMs(Long.parseLong(v));
                        case "errorRate" -> o.withErrorRate(Double.parseDouble(v));
                        case "bodyBytes" -> o.withBodyBytes(Integer.parseInt(v));
                        case "cacheControl" -> o.withCacheControl(v.isEmpty() ? null : v);
                        case "chunked" -> o.withChunked(Boolean.parseBoolean(v));
                        default -> throw new IllegalArgumentException("Unknown fake upstream option: --" + e.getKey());
                    };
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid value for --" + e.getKey() + ": " + v, ex);
                }
            }
            return o;
        }
    }

    private static final Gson GSON = new Gson();

    private final HttpServer server;
    private final ExecutorService executor;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final LongAdder total = new LongAdder();
    private final Map<String, LongAdder> perQuery = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Random random = new Random(42);
    private volatile Options options;

    private FakeUpstreamServer(HttpServer server, ExecutorService executor, Options options) {
        this.server = server;
        this.executor = executor;
        this.options = options;
    }

    /** Starts a fake upstream on {@code 127.0.0.1:port} (0 = a free port). */
    public static FakeUpstreamServer start(int port, Options options) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        FakeUpstreamServer fake = new FakeUpstreamServer(server, executor, options);
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port());
    }

    public URI forecastUrl() {
        return URI.create(baseUri() + "/v1/forecast");
    }

    public URI searchUrl() {
        return URI.create(baseUri() + "/v1/search");
    }

    public Options options() {
        return options;
    }

    public void options(Options newOptions) {
        this.options = newOptions;
    }

    /** Requests received on either route. */
    public long totalRequests() {
        return total.sum();
    }

    /** Requests received for {@code pathAndRawQuery}, e.g. {@code /v1/search?name=Berlin}. */
    public long requestsFor(String pathAndRawQuery) {
        LongAdder count = perQuery.get(pathAndRawQuery);
        return count == null ? 0 : count.sum();
    }

    public Map<String, Long> requestCounts() {
        Map<String, Long> counts = new TreeMap<>();
        perQuery.forEach((k, v) -> counts.put(k, v.sum()));
        return counts;
    }

    public void resetStats() {
        total.reset();
        perQuery.clear();
    }

    /** Stops the server and releases hanging and trickling requests. */
    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    // --- request handling ---

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (path.equals("/__stats")) {
                respond(exchange, 200, json(Map.of("total", totalRequests(), "perQuery", requestCounts())), null, false);
                return;
            }
            boolean forecast = path.equals("/v1/forecast");
            if (!forecast && !path.equals("/v1/search")) {
                respond(exchange, 404, error("Not found"), null, false);
                return;
            }
            total.increment();
            perQuery.computeIfAbsent(rawQuery == null ? path : path + "?" + rawQuery, k -> new LongAdder())
                    .increment();

            Options o = options;
            long delay = o.latencyMs() + (o.jitterMs() > 0 ? nextLong(o.jitterMs() + 1) : 0);
            if (delay > 0 && closed.await(delay, TimeUnit.MILLISECONDS)) {
                return;
            }
            switch (o.mode()) {
                case HANG -> closed.await();
                case ERROR500 -> respond(exchange, 500, error("Fake upstream error"), null, o.chunked());
                case STATUS429 -> respond(exchange, 429,
                        error("Minutely API request limit exceeded. Please try again in one minute."), null,
                        o.chunked());
                case NORMAL, TRICKLE -> {
                    if (o.mode() == Mode.NORMAL && o.errorRate() > 0 && nextDouble() < o.errorRate()) {
                        respond(exchange, 500, error("Fake upstream error"), null, o.chunked());
                        return;
                    }
                    Map<String, List<String>> params = parse(rawQuery);
                    String missing = forecast
                            ? (params.containsKey("latitude") && params.containsKey("longitude") ? null
                                    : "Parameter 'latitude' and 'longitude' are required")
                            : (params.containsKey("name") ? null : "Parameter 'name' is required");
                    if (missing != null) {
                        respond(exchange, 400, error(missing), null, o.chunked());
                        return;
                    }
                    Map<String, Object> body = forecast ? forecastBody(params) : searchBody(params);
                    body.put("fake", Map.of("request", sequence.incrementAndGet(), "query",
                            rawQuery == null ? "" : rawQuery));
                    byte[] bytes = pad(json(body), o.bodyBytes());
                    if (o.mode() == Mode.TRICKLE) {
                        trickle(exchange, bytes, o.cacheControl());
                    } else {
                        respond(exchange, 200, bytes, o.cacheControl(), o.chunked());
                    }
                }
            }
        } catch (IOException e) {
            // The client went away (for example, it cancelled after a deadline or size limit).
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String cacheControl, boolean chunked)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (cacheControl != null) {
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        }
        exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void trickle(HttpExchange exchange, byte[] body, String cacheControl)
            throws IOException, InterruptedException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (cacheControl != null) {
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
        }
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (byte b : body) {
                out.write(b);
                out.flush();
                if (closed.await(TRICKLE_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        }
    }

    private static Map<String, Object> forecastBody(Map<String, List<String>> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", number(params.get("latitude").get(0)));
        body.put("longitude", number(params.get("longitude").get(0)));
        body.put("generationtime_ms", 0.1);
        body.put("utc_offset_seconds", 0);
        body.put("timezone", "GMT");
        body.put("timezone_abbreviation", "GMT");
        body.put("elevation", 38.0);
        Map<String, Object> units = new LinkedHashMap<>();
        units.put("time", "iso8601");
        units.put("interval", "seconds");
        units.put("temperature_2m", "°C");
        units.put("relative_humidity_2m", "%");
        units.put("apparent_temperature", "°C");
        units.put("weather_code", "wmo code");
        units.put("wind_speed_10m", "km/h");
        units.put("wind_direction_10m", "°");
        units.put("is_day", "");
        body.put("current_units", units);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("time", "2026-10-22T19:45");
        current.put("interval", 900);
        current.put("temperature_2m", 12.3);
        current.put("relative_humidity_2m", 62);
        current.put("apparent_temperature", 9.3);
        current.put("weather_code", 3);
        current.put("wind_speed_10m", 12.3);
        current.put("wind_direction_10m", 220);
        current.put("is_day", 0);
        body.put("current", current);
        return body;
    }

    private static Map<String, Object> searchBody(Map<String, List<String>> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        String name = params.get("name").get(0);
        if (name.length() >= 2) { // Like the real API: no "results" key for one-character names.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", 2950159);
            result.put("name", name);
            result.put("latitude", 52.52437);
            result.put("longitude", 13.41053);
            result.put("elevation", 74.0);
            result.put("feature_code", "PPLC");
            result.put("country_code", "DE");
            result.put("timezone", "Europe/Berlin");
            result.put("country", "Germany");
            result.put("admin1", "State of Berlin");
            body.put("results", List.of(result));
        }
        body.put("generationtime_ms", 0.1);
        return body;
    }

    private static Map<String, List<String>> parse(String rawQuery) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String segment : rawQuery.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            String name = decode(eq < 0 ? segment : segment.substring(0, eq));
            String value = eq < 0 ? "" : decode(segment.substring(eq + 1));
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static Object number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static byte[] error(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("error", true);
        return json(body);
    }

    private static byte[] json(Object value) {
        return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    /** Pads a JSON object to exactly {@code bodyBytes} bytes with a {@code "padding"} field, when it is shorter. */
    static byte[] pad(byte[] json, int bodyBytes) {
        String prefix = ",\"padding\":\"";
        int fill = bodyBytes - json.length - prefix.length() - 1; // closing quote; '}' replaces the original one
        if (fill <= 0) {
            return json;
        }
        StringBuilder padded = new StringBuilder(bodyBytes);
        padded.append(new String(json, 0, json.length - 1, StandardCharsets.UTF_8));
        padded.append(prefix).append("x".repeat(fill)).append("\"}");
        return padded.toString().getBytes(StandardCharsets.UTF_8);
    }

    private long nextLong(long bound) {
        return (long) (random.nextDouble() * bound); // java.util.Random is thread-safe.
    }

    private double nextDouble() {
        return random.nextDouble();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "FakeUpstreamServer[%s, %s]", baseUri(), options);
    }
}
