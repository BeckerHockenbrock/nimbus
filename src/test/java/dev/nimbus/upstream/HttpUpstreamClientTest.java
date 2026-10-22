package dev.nimbus.upstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.bench.FakeUpstreamServer.Mode;
import dev.nimbus.bench.FakeUpstreamServer.Options;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(30)
class HttpUpstreamClientTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SHORT_DEADLINE = Duration.ofMillis(500);
    private static final long MIB = 1024 * 1024;

    private FakeUpstreamServer fake;
    private HttpUpstreamClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (fake != null) {
            fake.close();
        }
    }

    private URI forecast() {
        return URI.create(fake.forecastUrl() + "?latitude=52.52&longitude=13.41");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"NORMAL, 200", "STATUS429, 429", "ERROR500, 500"})
    void statusesAreResponsesNotExceptions(Mode mode, int status) throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withMode(mode));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);

        UpstreamResponse response = client.fetch(forecast());

        assertEquals(status, response.status());
        assertTrue(response.body().length > 0);
        assertTrue(response.latencyNanos() > 0);
        assertEquals(1, fake.totalRequests());
    }

    @Test
    void upstream400IsAResponse() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults());
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);

        UpstreamResponse response = client.fetch(URI.create(fake.forecastUrl() + "?latitude=1"));

        assertEquals(400, response.status());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("\"error\":true"));
    }

    @Test
    void headersAreExposed() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withCacheControl("public, max-age=86400"));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);

        UpstreamResponse response = client.fetch(URI.create(fake.searchUrl() + "?name=Berlin"));

        assertEquals("public, max-age=86400", response.firstHeader("cache-control"));
        assertEquals("application/json; charset=utf-8", response.contentType());
        assertEquals(String.valueOf(response.body().length), response.firstHeader("Content-Length"));
    }

    @Test
    void hangTimesOutWithinDeadline() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withMode(Mode.HANG));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, SHORT_DEADLINE, MIB);

        assertTimesOut(() -> client.fetch(forecast()));
    }

    /** The deadline covers the body, not just the headers (JDK-8208693). */
    @Test
    void trickleTimesOutWithinDeadline() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withMode(Mode.TRICKLE));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, SHORT_DEADLINE, MIB);

        assertTimesOut(() -> client.fetch(forecast()));
    }

    private static void assertTimesOut(Runnable call) {
        long start = System.nanoTime();
        UpstreamException e = assertThrows(UpstreamException.class, call::run);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(UpstreamException.Kind.TIMEOUT, e.kind());
        assertTrue(elapsedMs >= SHORT_DEADLINE.toMillis() - 50, "waited for the deadline: " + elapsedMs + " ms");
        assertTrue(elapsedMs < SHORT_DEADLINE.toMillis() + 1000, "within deadline + 1 s: " + elapsedMs + " ms");
    }

    @Test
    void chunkedBodyOverLimitIsAbortedQuickly() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withChunked(true).withBodyBytes((int) (10 * MIB)));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(20), MIB);

        long start = System.nanoTime();
        UpstreamException e = assertThrows(UpstreamException.class, () -> client.fetch(forecast()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(UpstreamException.Kind.TOO_LARGE, e.kind());
        assertTrue(elapsedMs < 5000, "aborted long before the deadline: " + elapsedMs + " ms");
    }

    @Test
    void bodyAtTheLimitIsAccepted() throws IOException {
        fake = FakeUpstreamServer.start(0, Options.defaults().withChunked(true).withBodyBytes((int) MIB));
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(10), MIB);

        assertEquals(MIB, client.fetch(forecast()).body().length);
    }

    /**
     * A server that declares a large Content-Length and then sends nothing. If the client waited for the body it
     * would time out; rejecting on the header gives TOO_LARGE right away.
     */
    @Test
    void contentLengthOverLimitIsRejectedBeforeTheBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = listener.accept()) {
                    readRequestHead(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + 10 * MIB + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    release.await();
                } catch (IOException | InterruptedException ignored) {
                    // Test over.
                }
            });
            client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);

            long start = System.nanoTime();
            UpstreamException e = assertThrows(UpstreamException.class,
                    () -> client.fetch(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/v1/forecast")));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(UpstreamException.Kind.TOO_LARGE, e.kind());
            assertTrue(elapsedMs < 2000, "did not wait for the body: " + elapsedMs + " ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    void connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);

        UpstreamException e = assertThrows(UpstreamException.class,
                () -> client.fetch(URI.create("http://127.0.0.1:" + closedPort + "/v1/forecast")));

        assertEquals(UpstreamException.Kind.CONNECTION, e.kind());
    }

    /** PLAN.md, Section 3.4, Invariant 2: only a fixed set of request headers goes upstream. */
    @Test
    void sendsFixedHeadersOnly() throws IOException {
        AtomicReference<HttpHeaders> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(HttpHeaders.of(exchange.getRequestHeaders(), (k, v) -> true));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            client = new HttpUpstreamClient(CONNECT_TIMEOUT, Duration.ofSeconds(5), MIB);
            client.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/search?name=x"));
        } finally {
            server.stop(0);
        }
        HttpHeaders headers = seen.get();
        assertEquals(HttpUpstreamClient.USER_AGENT, headers.firstValue("User-Agent").orElseThrow());
        assertTrue(HttpUpstreamClient.USER_AGENT.startsWith("nimbus-proxy/"));
        assertEquals("*/*", headers.firstValue("Accept").orElseThrow());
        assertFalse(headers.firstValue("Accept-Encoding").isPresent());
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int state = 0; // Counts the bytes of "\r\n\r\n" seen so far.
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
                    ? state + 1 : (b == '\r' ? 1 : 0);
        }
    }
}
