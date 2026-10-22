package dev.nimbus.server;

import com.google.gson.Gson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.proxy.ProxyResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Response helpers for {@link HttpExchange}.
 *
 * <p>{@code sendResponseHeaders} is always given the exact body length, or {@code -1} for no body. ({@code 0} would
 * mean chunked encoding.)
 */
final class HttpUtil {

    private static final Gson GSON = new Gson();

    private HttpUtil() {
    }

    static void sendBytes(HttpExchange exchange, int status, Map<String, String> headers, byte[] body)
            throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        headers.forEach(responseHeaders::set);
        boolean noBody = body.length == 0 || status == 204 || status == 304 || status < 200
                || "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, noBody ? -1 : body.length);
        if (!noBody) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        sendBytes(exchange, status, Map.of("Content-Type", ProxyResponse.JSON_CONTENT_TYPE),
                GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    static void sendError(HttpExchange exchange, int status, String reason, Map<String, String> extraHeaders)
            throws IOException {
        ProxyResponse error = ProxyResponse.error(status, reason, Outcome.INVALID, extraHeaders);
        sendBytes(exchange, status, error.headers(), error.body());
    }
}
