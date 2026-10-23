package dev.nimbus.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
 * and the exchange is always closed.
 */
final class ProxyHandler implements HttpHandler {

    private static final System.Logger LOG = System.getLogger(ProxyHandler.class.getName());
    private static final System.Logger ACCESS = System.getLogger("dev.nimbus.access");

    private final ProxyService service;

    ProxyHandler(ProxyService service) {
        this.service = service;
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
                response = service.handle(request);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Internal error handling " + uri, e);
                response = ProxyResponse.error(500, "Internal proxy error", Outcome.INTERNAL_ERROR);
            }
            HttpUtil.sendBytes(exchange, response.status(), response.headers(), response.body());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Client disconnected during response: " + e.getMessage());
        } finally {
            exchange.close();
            if (response != null) {
                ACCESS.log(System.Logger.Level.INFO, String.format(Locale.ROOT, "%s \"%s %s\" %d %s %dB %.3fms",
                        clientId, exchange.getRequestMethod(), uri.getRawQuery() == null ? uri.getRawPath()
                                : uri.getRawPath() + "?" + uri.getRawQuery(),
                        response.status(), response.outcome(), response.body().length,
                        (System.nanoTime() - start) / 1_000_000.0));
            }
        }
    }
}
