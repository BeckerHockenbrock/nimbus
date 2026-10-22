package dev.nimbus.proxy;

/**
 * A proxied request, independent of the HTTP server.
 *
 * @param path     the raw request path
 * @param rawQuery the raw (still percent-encoded) query, or {@code null}
 * @param clientId the client's socket IP address
 */
public record ProxyRequest(String method, String path, String rawQuery, String clientId) {
}
