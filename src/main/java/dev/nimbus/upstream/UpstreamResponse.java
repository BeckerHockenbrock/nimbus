package dev.nimbus.upstream;

import java.net.http.HttpHeaders;
import java.util.Objects;

/**
 * A complete upstream response received within the size limit and deadline, with any status.
 *
 * <p>{@code body} is never copied after download and must be treated as immutable.
 *
 * @param latencyNanos time from sending the request until the body was fully received
 */
public record UpstreamResponse(int status, byte[] body, HttpHeaders headers, long latencyNanos) {

    public UpstreamResponse {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(headers, "headers");
    }

    /** The {@code Content-Type} header, or {@code null} if absent. */
    public String contentType() {
        return firstHeader("Content-Type");
    }

    /** The first value of header {@code name} (case-insensitive), or {@code null} if absent. */
    public String firstHeader(String name) {
        return headers.firstValue(name).orElse(null);
    }
}
