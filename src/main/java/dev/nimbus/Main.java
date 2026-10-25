package dev.nimbus;

import dev.nimbus.bench.FakeUpstreamServer;
import dev.nimbus.cache.Cache;
import dev.nimbus.cache.CacheEntry;
import dev.nimbus.cache.FreshnessPolicy;
import dev.nimbus.cache.LinkedHashMapCache;
import dev.nimbus.config.NimbusConfig;
import dev.nimbus.metrics.ProxyMetrics;
import dev.nimbus.proxy.ProxyService;
import dev.nimbus.ratelimit.ClientRateLimiter;
import dev.nimbus.ratelimit.UpstreamBudget;
import dev.nimbus.server.ProxyServer;
import dev.nimbus.upstream.HttpUpstreamClient;

import java.io.IOException;
import java.io.PrintStream;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * Entry point. Parses the mode and {@code --key=value} flags, builds {@link NimbusConfig}, and wires the
 * components together with plain constructors.
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    static final List<String> MODES = List.of("server", "gui", "all", "bench", "fake-upstream");
    static final String DEFAULT_MODE = "all";

    static final String USAGE = """
            Usage: java -jar nimbus.jar [mode] [--key=value ...]

            Modes:
              server          Run the caching proxy only
              gui             Run the Swing client only (connects to nimbus.gui.proxyUrl)
              all             Run the proxy and the Swing client in one JVM (default)
              bench           Run the load generator against loopback upstreams
              fake-upstream   Run the loopback fake Open-Meteo server (default port 9090)

            Options:
              --config=<file>      Load settings from a properties file
              --<key>=<value>      Override a setting, e.g. --nimbus.cache.maxBytes=1048576
              --port=<n>           Shortcut for --nimbus.port=<n>
              -h, --help           Show this help

            fake-upstream options:
              --port=<n> --mode=normal|error500|status429|hang|trickle --latencyMs=<n> --jitterMs=<n>
              --errorRate=<0..1> --bodyBytes=<n> --cacheControl=<value> --chunked=true|false
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs Nimbus and returns the process exit code. Server modes block until the JVM shuts down. */
    static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> positional = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(USAGE);
                return EXIT_OK;
            }
            if (arg.startsWith("-")) {
                if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
                    return usageError(err, "Unknown option: " + arg);
                }
                flags.add(arg);
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() > 1) {
            return usageError(err, "Expected at most one mode, got: " + String.join(" ", positional));
        }

        String mode = positional.isEmpty() ? DEFAULT_MODE : positional.get(0);
        if (!MODES.contains(mode)) {
            return usageError(err, "Unknown mode: " + mode);
        }
        try {
            return switch (mode) {
                case "server" -> runServer(flags, out);
                case "fake-upstream" -> runFakeUpstream(flags, out);
                default -> {
                    out.println(mode + ": not implemented yet");
                    yield EXIT_OK;
                }
            };
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static int runServer(List<String> flags, PrintStream out) throws IOException {
        NimbusConfig config = NimbusConfig.load(flags, System.getenv(), System.getProperties());
        HttpUpstreamClient upstream = new HttpUpstreamClient(config.upstreamConnectTimeout(),
                config.upstreamDeadline(), config.upstreamMaxResponseBytes());
        Cache<String, CacheEntry> cache = new LinkedHashMapCache<>(config.cacheMaxEntries(), config.cacheMaxBytes(),
                (key, entry) -> entry.weightBytes(key));
        InstantSource clock = InstantSource.system();
        ClientRateLimiter rateLimiter = new ClientRateLimiter(config.clientCapacity(), config.clientRefillPerSecond(),
                clock);
        UpstreamBudget budget = UpstreamBudget.from(config, clock);
        ProxyMetrics metrics = new ProxyMetrics(config, cache, budget, clock);
        ProxyService service = new ProxyService(config.routes(), cache, upstream, rateLimiter, budget,
                new Semaphore(config.upstreamMaxConcurrent(), true), FreshnessPolicy.from(config), metrics, clock,
                config);
        ProxyServer server = new ProxyServer(config, service, metrics, rateLimiter);
        try {
            server.start();
        } catch (IOException e) {
            upstream.close();
            throw new IOException("Cannot listen on " + config.host() + ":" + config.port() + ": " + e.getMessage(), e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            upstream.close();
        }, "nimbus-shutdown"));
        out.println("Nimbus proxy listening on http://" + config.host() + ":" + server.port());
        out.println("Upstreams: " + config.forecastUpstream() + ", " + config.geocodingUpstream());
        return awaitShutdown();
    }

    private static int runFakeUpstream(List<String> flags, PrintStream out) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        int port = FakeUpstreamServer.DEFAULT_PORT;
        for (String flag : flags) {
            int eq = flag.indexOf('=');
            String key = flag.substring(2, eq);
            String value = flag.substring(eq + 1);
            if (key.equals("port")) {
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid value for --port: " + value, e);
                }
            } else {
                options.put(key, value);
            }
        }
        FakeUpstreamServer fake = FakeUpstreamServer.start(port, FakeUpstreamServer.Options.fromFlags(options));
        Runtime.getRuntime().addShutdownHook(new Thread(fake::close, "fake-upstream-shutdown"));
        out.println("Fake upstream listening on " + fake.baseUri() + " " + fake.options());
        return awaitShutdown();
    }

    /** Blocks the main thread; the shutdown hook stops the servers when the JVM exits (for example on Ctrl+C). */
    private static int awaitShutdown() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return EXIT_OK;
    }

    private static int usageError(PrintStream err, String message) {
        err.println(message);
        err.println();
        err.print(USAGE);
        return EXIT_USAGE;
    }
}
