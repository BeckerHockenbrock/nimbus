package dev.nimbus.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nimbus.metrics.ProxyMetrics;
import dev.nimbus.proxy.Outcome;
import dev.nimbus.proxy.ProxyRequest;
import dev.nimbus.proxy.ProxyResponse;
import dev.nimbus.proxy.ProxyService;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Thin adapter between {@link HttpExchange} and {@link ProxyService}. Every decision lives in the service.
 *
 * <p>Any exception from the service becomes a JSON 500 ({@code INTERNAL_ERROR}) rather than a dropped connection,
 * and the exchange is always closed. Server latency runs from the first line of {@link #handle} until the response
 * has been written and the exchange closed.
 */
final class ProxyHandler implements HttpHandler {

    private static final System.Logger LOG = System.getLogger(ProxyHandler.class.getName());
    private static final System.Logger ACCESS = System.getLogger("dev.nimbus.access");

    private final ProxyService service;
    private final ProxyMetrics metrics;

    ProxyHandler(ProxyService service, ProxyMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) {
        long start = System.nanoTime();
        URI uri = exchange.getRequestURI();
        String clientId = exchange.getRemoteAddress().getAddress().getHostAddress();
        ProxyResponse response = null;
        try {
            ProxyRequest request = new ProxyRequest(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                    clientId);
            try {
                response = service.handle(request); // Counts the outcome.
            } catch (RuntimeException e) {
                // ProxyService catches its own exceptions, so this is a last resort; count it here instead.
                LOG.log(System.Logger.Level.ERROR, "Internal error handling " + uri, e);
                response = ProxyResponse.error(500, "Internal proxy error", Outcome.INTERNAL_ERROR);
                metrics.recordOutcome(Outcome.INTERNAL_ERROR);
            }
            HttpUtil.sendBytes(exchange, response.status(), response.headers(), response.body());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Client disconnected during response: " + e.getMessage());
        } finally {
            exchange.close();
            long nanos = System.nanoTime() - start;
            if (response != null) {
                metrics.recordLatency(response.outcome(), nanos);
                ACCESS.log(System.Logger.Level.INFO, String.format(Locale.ROOT, "%s %s %d %s %.3fms %s",
                        exchange.getRequestMethod(), uri.getRawQuery() == null ? uri.getRawPath()
                                : uri.getRawPath() + "?" + uri.getRawQuery(),
                        response.status(), response.outcome(), nanos / 1_000_000.0, clientId));
            }
        }
    }
}
