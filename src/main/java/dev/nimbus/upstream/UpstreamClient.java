package dev.nimbus.upstream;

import java.net.URI;

/** Sends one GET request upstream. Implementations must be thread-safe. */
public interface UpstreamClient {

    /**
     * Fetches {@code uri}. Any HTTP status is a normal return value.
     *
     * @throws UpstreamException if no complete response arrived within the limits
     */
    UpstreamResponse fetch(URI uri) throws UpstreamException;
}
