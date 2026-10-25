package dev.nimbus.server;

import com.sun.net.httpserver.HttpServer;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.ProxyMetrics;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.ratelimit.ClientRateLimiter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP server: binds {@code nimbus.host:nimbus.port} (port 0 picks a free port) and runs one virtual thread
 * per request. Without {@code setExecutor}, {@link HttpServer} would handle one request at a time.
 *
 * <p>The proxy handler is registered at {@code /} so that unknown paths also get a JSON 404; {@code /metrics} and
 * {@code /health} are matched first because they are longer prefixes, and are not counted in the metrics.
 *
 * <p>The server also owns the background task that drops idle client rate-limit buckets every 60 s.
 *
 * <p>Lifecycle methods are not thread-safe; call them from one thread.
 */
public final class ProxyServer {

    private static final long IDLE_EVICTION_PERIOD_SECONDS = 60;

    private final NimbusConfig config;
    private final ProxyService service;
    private final ProxyMetrics metrics;
    private final ClientRateLimiter rateLimiter;
    private volatile HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService maintenance;

    public ProxyServer(NimbusConfig config, ProxyService service, ProxyMetrics metrics, ClientRateLimiter rateLimiter) {
        this.config = config;
        this.service = service;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
    }

    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("already started");
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        created.setExecutor(executor);
        created.createContext("/", new ProxyHandler(service, metrics));
        created.createContext("/metrics", new MetricsHandler(metrics));
        created.createContext("/health",
                new HealthHandler(List.of(config.forecastUpstream(), config.geocodingUpstream())));
        created.start();
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nimbus-idle-bucket-eviction");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleAtFixedRate(() -> rateLimiter.evictIdle(config.clientIdleEvict()),
                IDLE_EVICTION_PERIOD_SECONDS, IDLE_EVICTION_PERIOD_SECONDS, TimeUnit.SECONDS);
        server = created;
    }

    /** The bound port (useful when configured with port 0). */
    public int port() {
        if (server == null) {
            throw new IllegalStateException("not started");
        }
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdown();
            maintenance.shutdownNow();
            server = null;
        }
    }
}
