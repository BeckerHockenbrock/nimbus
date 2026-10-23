package dev.nimbus.upstream;

import dev.nimbus.upstream.LimitedBodySubscriber.ResponseTooLargeException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link UpstreamClient} over one shared {@link HttpClient} (thread-safe, pools keep-alive connections).
 *
 * <p>Every request sends only {@code User-Agent} and {@code Accept}, so the response depends on the URL alone
 * (PLAN.md, Section 3.4, Invariant 2). Client headers are never forwarded and {@code Accept-Encoding} is never sent.
 *
 * <p>The deadline covers headers <em>and</em> body. On Java 21, {@code HttpRequest.timeout} stops at the response
 * headers (JDK-8208693), so the deadline is enforced with {@code sendAsync} + {@code get(timeout)} +
 * {@code cancel(true)}.
 */
public final class HttpUpstreamClient implements UpstreamClient, AutoCloseable {

    public static final String USER_AGENT = "nimbus-proxy/" + version();

    private final HttpClient client;
    private final Duration deadline;
    private final long maxResponseBytes;

    public HttpUpstreamClient(Duration connectTimeout, Duration deadline, long maxResponseBytes) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1) // Open-Meteo serves HTTP/1.1 (PLAN.md, Appendix A).
                .build();
        this.deadline = deadline;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public UpstreamResponse fetch(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(request, LimitedBodySubscriber.handler(maxResponseBytes));
        try {
            HttpResponse<byte[]> response = future.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
            return new UpstreamResponse(response.statusCode(), response.body(), response.headers(),
                    System.nanoTime() - start);
        } catch (TimeoutException e) {
            future.cancel(true); // Documented as an attempt to cancel the HTTP exchange.
            throw new UpstreamException(UpstreamException.Kind.TIMEOUT,
                    "No complete response within " + deadline.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UpstreamException(UpstreamException.Kind.INTERRUPTED, "Interrupted while waiting for upstream", e);
        } catch (ExecutionException e) {
            throw map(e.getCause());
        }
    }

    private static RuntimeException map(Throwable failure) {
        boolean io = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLargeException) {
                return new UpstreamException(UpstreamException.Kind.TOO_LARGE, t.getMessage(), failure);
            }
            if (t instanceof HttpTimeoutException) {
                return new UpstreamException(UpstreamException.Kind.TIMEOUT, t.getMessage(), failure);
            }
            io |= t instanceof IOException;
        }
        if (io) {
            return new UpstreamException(UpstreamException.Kind.CONNECTION, String.valueOf(failure.getMessage()), failure);
        }
        // Not an I/O problem: a bug, which the caller reports as INTERNAL_ERROR.
        return new IllegalStateException("Unexpected upstream client failure", failure);
    }

    @Override
    public void close() {
        client.close();
    }

    private static String version() {
        String version = HttpUpstreamClient.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
