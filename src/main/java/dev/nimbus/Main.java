package dev.nimbus;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. Parses the mode and {@code --key=value} flags and starts the requested components.
 *
 * <p>Phase 0: every mode is a placeholder that reports it is not implemented yet.
 */
public final class Main {

    static final int EXIT_OK = 0;
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
              fake-upstream   Run the loopback fake Open-Meteo server

            Options:
              --config=<file>      Load settings from a properties file
              --<key>=<value>      Override a setting, e.g. --nimbus.cache.maxBytes=1048576
              --port=<n>           Shortcut for --nimbus.port=<n>
              -h, --help           Show this help
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Runs Nimbus and returns the process exit code. */
    static int run(String[] args) {
        return run(args, System.out, System.err);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(USAGE);
                return EXIT_OK;
            }
            if (arg.startsWith("-")) {
                if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
                    return usageError(err, "Unknown option: " + arg);
                }
                // --key=value flags are accepted here and applied by NimbusConfig (Phase 1).
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
        out.println(mode + ": not implemented yet");
        return EXIT_OK;
    }

    private static int usageError(PrintStream err, String message) {
        err.println(message);
        err.println();
        err.print(USAGE);
        return EXIT_USAGE;
    }
}
