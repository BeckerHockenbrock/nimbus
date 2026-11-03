package dev.nimbus.client;

import com.google.gson.*;
import dev.nimbus.metrics.MetricsSnapshot;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Blocking proxy client. Invoke from a background thread. */
public final class NimbusClient {
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Gson gson = new Gson();
    public NimbusClient(URI base) { this.base = base; }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private URI uri(String path) { return URI.create(base.toString().replaceAll("/+$", "") + path); }
    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Proxy returned HTTP " + response.statusCode() + ": " + response.body());
        return response;
    }
    private static <T> ClientResult<T> result(T value, HttpResponse<?> response, long start) {
        return new ClientResult<>(value, response.headers().firstValue("X-Cache").orElse("–"), String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1_000_000.0));
    }
    public ClientResult<List<Location>> search(String name) throws IOException, InterruptedException {
        long start = System.nanoTime();
        var response = get("/v1/search?name=" + encode(name) + "&count=10&language=en&format=json");
        return result(parseLocations(response.body()), response, start);
    }
    public ClientResult<CurrentWeather> weather(Location location, boolean fahrenheit) throws IOException, InterruptedException {
        String query = "/v1/forecast?latitude=" + encode(location.latitude()) + "&longitude=" + encode(location.longitude())
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,is_day&timezone=auto"
                + (fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
        long start = System.nanoTime();
        var response = get(query);
        return result(parseWeather(response.body()), response, start);
    }
    public MetricsSnapshot metrics() throws IOException, InterruptedException {
        try { return gson.fromJson(get("/metrics").body(), MetricsSnapshot.class); }
        catch (JsonParseException e) { throw new IOException("Invalid metrics response", e); }
    }
    public void health() throws IOException, InterruptedException { get("/health"); }
    public static List<Location> parseLocations(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<Location> locations = new ArrayList<>();
            if (root.has("results") && !root.get("results").isJsonNull()) {
                for (JsonElement e : root.getAsJsonArray("results")) {
                    JsonObject o = e.getAsJsonObject();
                    String lat = string(o, "latitude"), lon = string(o, "longitude");
                    if (lat == null || lon == null) throw new JsonParseException("Missing coordinates");
                    locations.add(new Location(string(o,"name"), lat, lon, string(o,"admin1"), string(o,"country"), string(o,"country_code")));
                }
            }
            return List.copyOf(locations);
        } catch (RuntimeException e) { throw new IOException("Invalid search response", e); }
    }
    public static CurrentWeather parseWeather(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject c = root.getAsJsonObject("current");
            if (c == null) throw new JsonParseException("Missing current weather");
            JsonObject units = root.getAsJsonObject("current_units");
            return new CurrentWeather(string(c,"time"), string(root,"timezone"), number(c,"temperature_2m"), number(c,"apparent_temperature"),
                    number(c,"relative_humidity_2m"), integer(c,"weather_code"), number(c,"wind_speed_10m"), number(c,"wind_direction_10m"),
                    integer(c,"is_day"), string(units,"temperature_2m"), string(units,"wind_speed_10m"));
        } catch (RuntimeException e) { throw new IOException("Invalid weather response", e); }
    }
    private static String string(JsonObject o, String key) { return o == null || !o.has(key) || o.get(key).isJsonNull() ? null : o.get(key).getAsString(); }
    private static Double number(JsonObject o, String key) { String s = string(o,key); return s == null ? null : Double.valueOf(s); }
    private static Integer integer(JsonObject o, String key) { String s = string(o,key); return s == null ? null : Integer.valueOf(s); }
}
