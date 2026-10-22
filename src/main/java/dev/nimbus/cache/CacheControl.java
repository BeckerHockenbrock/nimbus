package dev.nimbus.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * The response {@code Cache-Control} directives Nimbus acts on (RFC 9111, Section 5.2.2).
 *
 * <ul>
 *   <li>Directive names are case-insensitive; multiple header lines and comma-separated lists are merged.</li>
 *   <li>Quoted values are unquoted, and commas inside quotes don't split directives.</li>
 *   <li>Qualified {@code no-cache="x"} and {@code private="x"} are treated as unqualified (stricter, still correct).</li>
 *   <li>A malformed or negative {@code max-age}/{@code s-maxage} is 0; a value too large to represent is
 *       2<sup>31</sup> (RFC 9111, Section 1.2.2). For a repeated directive, the first occurrence wins
 *       (Section 4.2.1).</li>
 * </ul>
 */
public record CacheControl(boolean noStore, boolean noCache, boolean isPrivate, boolean mustRevalidate,
                           boolean proxyRevalidate, OptionalLong maxAge, OptionalLong sMaxAge) {

    public static final CacheControl NONE =
            new CacheControl(false, false, false, false, false, OptionalLong.empty(), OptionalLong.empty());

    static final long MAX_DELTA_SECONDS = 1L << 31;

    public static CacheControl parse(List<String> headerValues) {
        boolean noStore = false;
        boolean noCache = false;
        boolean isPrivate = false;
        boolean mustRevalidate = false;
        boolean proxyRevalidate = false;
        OptionalLong maxAge = OptionalLong.empty();
        OptionalLong sMaxAge = OptionalLong.empty();
        for (String line : headerValues) {
            for (String directive : split(line)) {
                int eq = directive.indexOf('=');
                String name = (eq < 0 ? directive : directive.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
                String value = eq < 0 ? null : unquote(directive.substring(eq + 1).trim());
                switch (name) {
                    case "no-store" -> noStore = true;
                    case "no-cache" -> noCache = true;
                    case "private" -> isPrivate = true;
                    case "must-revalidate" -> mustRevalidate = true;
                    case "proxy-revalidate" -> proxyRevalidate = true;
                    case "max-age" -> {
                        if (maxAge.isEmpty()) {
                            maxAge = OptionalLong.of(deltaSeconds(value));
                        }
                    }
                    case "s-maxage" -> {
                        if (sMaxAge.isEmpty()) {
                            sMaxAge = OptionalLong.of(deltaSeconds(value));
                        }
                    }
                    default -> {
                        // Not used by Nimbus (public, immutable, stale-while-revalidate, ...).
                    }
                }
            }
        }
        return new CacheControl(noStore, noCache, isPrivate, mustRevalidate, proxyRevalidate, maxAge, sMaxAge);
    }

    /**
     * Parses delta-seconds ({@code 1*DIGIT}). Anything else, including a negative number or a missing value, is 0.
     * Shared with the {@code Age} header, which uses the same syntax.
     */
    static long deltaSeconds(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        if (value.length() > 10) {
            return MAX_DELTA_SECONDS;
        }
        return Math.min(Long.parseLong(value), MAX_DELTA_SECONDS);
    }

    /** Splits a header line on commas that are outside quoted strings. Empty items are dropped. */
    private static List<String> split(String line) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\' && i + 1 < line.length()) {
                current.append(c).append(line.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (c == ',' && !quoted) {
                addItem(items, current);
            } else {
                current.append(c);
            }
        }
        addItem(items, current);
        return items;
    }

    private static void addItem(List<String> items, StringBuilder current) {
        String item = current.toString().trim();
        if (!item.isEmpty()) {
            items.add(item);
        }
        current.setLength(0);
    }

    private static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                c = value.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
    }
}
