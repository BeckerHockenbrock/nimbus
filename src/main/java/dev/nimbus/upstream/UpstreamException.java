package dev.nimbus.upstream;

/** The upstream call failed without producing a complete response. */
public final class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** The total deadline (headers and body) passed. */
        TIMEOUT,
        /** Connecting, sending, or receiving failed. */
        CONNECTION,
        /** The response body exceeded {@code nimbus.upstream.maxResponseBytes}. */
        TOO_LARGE,
        /** The calling thread was interrupted; its interrupt flag has been restored. */
        INTERRUPTED
    }

    private final Kind kind;

    public UpstreamException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public UpstreamException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
