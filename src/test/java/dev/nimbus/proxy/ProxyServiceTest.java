package dev.nimbus.proxy;

import static dev.nimbus.testutil.FakeUpstreamClient.response;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.testutil.FakeUpstreamClient;
import dev.nimbus.upstream.UpstreamException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProxyServiceTest {

    private static final String CLIENT = "127.0.0.1";

    private final NimbusConfig config = NimbusConfig.withOverrides(Map.of(
            "nimbus.forecast.upstream", "http://upstream.test/v1/forecast",
            "nimbus.geocoding.upstream", "http://geo.test/v1/search"));
    private final FakeUpstreamClient upstream = new FakeUpstreamClient();
    private final ProxyService service = new ProxyService(config.routes(), upstream, config);

    private ProxyResponse get(String path, String rawQuery) {
        return service.handle(new ProxyRequest("GET", path, rawQuery, CLIENT));
    }

    private static JsonObject json(ProxyResponse response) {
        return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void assertProxyError(ProxyResponse response, int status, Outcome outcome) {
        assertEquals(status, response.status());
        assertEquals(outcome, response.outcome());
        assertEquals(ProxyResponse.JSON_CONTENT_TYPE, response.headers().get("Content-Type"));
        assertNull(response.headers().get("X-Cache"), "errors have no X-Cache");
        JsonObject body = json(response);
        assertTrue(body.get("error").getAsBoolean());
        assertEquals("proxy", body.get("source").getAsString());
        assertFalse(body.get("reason").getAsString().isBlank());
    }

    @Test
    void upstreamReceivesTheCanonicalUrl() {
        get("/v1/forecast", "longitude=13.41053&current=temperature_2m&latitude=52.52437&current=is_day");

        assertEquals(URI.create("http://upstream.test/v1/forecast"
                        + "?current=temperature_2m&current=is_day&latitude=52.52437&longitude=13.41053"),
                upstream.lastCall());

        get("/v1/search", "name=S%C3%A3o+Paulo&count=1");
        assertEquals(URI.create("http://geo.test/v1/search?count=1&name=S%C3%A3o+Paulo"), upstream.lastCall());
    }

    @Test
    void relaysStatusBodyAndHeaders() {
        byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
        upstream.respond(response(200, body,
                "Content-Type", "application/json; charset=utf-8",
                "Cache-Control", "public",
                "Cache-Control", "max-age=86400",
                "Set-Cookie", "not=relayed"));

        ProxyResponse response = get("/v1/search", "name=Berlin");

        assertEquals(200, response.status());
        assertEquals(Outcome.MISS, response.outcome());
        assertArrayEquals(body, response.body());
        assertEquals("application/json; charset=utf-8", response.headers().get("Content-Type"));
        assertEquals("public, max-age=86400", response.headers().get("Cache-Control"));
        assertEquals("MISS", response.headers().get("X-Cache"));
        assertEquals("1.000", response.headers().get("X-Upstream-Latency-Ms"));
        assertNull(response.headers().get("Set-Cookie"));
    }

    @Test
    void noCacheControlFromUpstreamMeansNoneRelayed() {
        upstream.respond(response(200, "{}", "Content-Type", "application/json"));

        assertNull(get("/v1/forecast", "latitude=1&longitude=2").headers().get("Cache-Control"));
    }

    @ParameterizedTest
    @CsvSource({"400", "404", "301"})
    void upstreamClientErrorsAreRelayedAsMiss(int status) {
        upstream.respond(response(status, "{\"reason\":\"Latitude must be in range\",\"error\":true}",
                "Content-Type", "application/json; charset=utf-8"));

        ProxyResponse response = get("/v1/forecast", "latitude=999&longitude=2");

        assertEquals(status, response.status());
        assertEquals(Outcome.MISS, response.outcome());
        assertEquals("Latitude must be in range", json(response).get("reason").getAsString());
    }

    @Test
    void unknownPathIs404WithoutUpstreamCall() {
        assertProxyError(get("/v1/archive", "latitude=1&longitude=2"), 404, Outcome.INVALID);
        assertProxyError(get("/", null), 404, Outcome.INVALID);
        assertProxyError(get("/v1/forecast/", "latitude=1&longitude=2"), 404, Outcome.INVALID);
        assertEquals(0, upstream.callCount());
    }

    @Test
    void nonGetIs405WithoutUpstreamCall() {
        ProxyResponse response = service.handle(new ProxyRequest("POST", "/v1/forecast", "latitude=1&longitude=2",
                CLIENT));

        assertProxyError(response, 405, Outcome.INVALID);
        assertEquals("GET", response.headers().get("Allow"));
        assertEquals(0, upstream.callCount());
    }

    @Test
    void badQueryIs400Or414WithoutUpstreamCall() {
        assertProxyError(get("/v1/forecast", "latitude=1"), 400, Outcome.INVALID);
        assertProxyError(get("/v1/search", "name=%zz"), 400, Outcome.INVALID);
        assertProxyError(get("/v1/search", "name=" + "a".repeat(config.maxQueryLength())), 414, Outcome.INVALID);
        assertEquals(0, upstream.callCount());
    }

    @Test
    void timeoutIs504() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.TIMEOUT, "slow"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 504, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void tooLargeIs502() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.TOO_LARGE, "big"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void connectionFailureIs502() {
        upstream.fail(new UpstreamException(UpstreamException.Kind.CONNECTION, "refused"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @ParameterizedTest
    @CsvSource({"500", "502", "503"})
    void upstream5xxIs502(int status) {
        upstream.respond(response(status, "{\"error\":true}"));

        assertProxyError(get("/v1/forecast", "latitude=1&longitude=2"), 502, Outcome.UPSTREAM_ERROR);
    }

    @Test
    void upstream429Is503WithRetryAfter() {
        upstream.respond(response(429, "{\"reason\":\"Minutely API request limit exceeded\",\"error\":true}"));

        ProxyResponse response = get("/v1/forecast", "latitude=1&longitude=2");

        assertProxyError(response, 503, Outcome.UPSTREAM_ERROR);
        assertEquals(String.valueOf(config.upstreamCooldown().toSeconds()), response.headers().get("Retry-After"));
    }

    @Test
    void longerUpstreamRetryAfterWins() {
        upstream.respond(response(429, "{}", "Retry-After", "120"));

        assertEquals("120", get("/v1/forecast", "latitude=1&longitude=2").headers().get("Retry-After"));
    }
}
