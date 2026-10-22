package dev.nimbus.proxy;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The response to a {@link ProxyRequest}. {@code body} may be an upstream or cached array and must not be modified.
 */
public record ProxyResponse(int status, Map<String, String> headers, byte[] body, Outcome outcome) {

    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final Gson GSON = new Gson();

    /** Proxy-generated error body: Open-Meteo's error shape plus a source field (PLAN.md, E6). */
    private record ErrorBody(boolean error, String reason, String source) {
    }

    public ProxyResponse {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** A JSON error response {@code {"error":true,"reason":"...","source":"proxy"}}. */
    public static ProxyResponse error(int status, String reason, Outcome outcome, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", JSON_CONTENT_TYPE);
        headers.putAll(extraHeaders);
        return new ProxyResponse(status, headers, errorBody(reason), outcome);
    }

    public static ProxyResponse error(int status, String reason, Outcome outcome) {
        return error(status, reason, outcome, Map.of());
    }

    /** The UTF-8 JSON bytes of a proxy error body. */
    public static byte[] errorBody(String reason) {
        return GSON.toJson(new ErrorBody(true, reason, "proxy")).getBytes(StandardCharsets.UTF_8);
    }
}
