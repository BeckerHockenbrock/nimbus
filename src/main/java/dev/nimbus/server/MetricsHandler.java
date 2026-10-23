package dev.nimbus.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nimbus.metrics.ProxyMetrics;

import java.io.IOException;
import java.util.Map;

/** {@code GET /metrics} → {@link ProxyMetrics#snapshot()} as JSON. Not itself counted in the metrics. */
final class MetricsHandler implements HttpHandler {

    private final ProxyMetrics metrics;

    MetricsHandler(ProxyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestURI().getRawPath().equals("/metrics")) {
                HttpUtil.sendError(exchange, 404, "Unknown path: " + exchange.getRequestURI().getRawPath(), Map.of());
            } else if (!exchange.getRequestMethod().equals("GET")) {
                HttpUtil.sendError(exchange, 405, "Method " + exchange.getRequestMethod() + " is not allowed; use GET",
                        Map.of("Allow", "GET"));
            } else {
                HttpUtil.sendJson(exchange, 200, metrics.snapshot());
            }
        } finally {
            exchange.close();
        }
    }
}
