package dev.nimbus.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /health} → {@code {"status":"ok","upstreams":[...]}}. The upstream URLs let the benchmark tool check
 * that it is talking to a fake upstream.
 */
final class HealthHandler implements HttpHandler {

    private record Health(String status, List<String> upstreams) {
    }

    private final Health health;

    HealthHandler(List<URI> upstreams) {
        this.health = new Health("ok", upstreams.stream().map(URI::toString).toList());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestURI().getRawPath().equals("/health")) {
                HttpUtil.sendError(exchange, 404, "Unknown path: " + exchange.getRequestURI().getRawPath(), Map.of());
            } else if (!exchange.getRequestMethod().equals("GET")) {
                HttpUtil.sendError(exchange, 405, "Method " + exchange.getRequestMethod() + " is not allowed; use GET",
                        Map.of("Allow", "GET"));
            } else {
                HttpUtil.sendJson(exchange, 200, health);
            }
        } finally {
            exchange.close();
        }
    }
}
