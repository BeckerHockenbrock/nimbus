package dev.nimbus.proxy;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Turns a raw query string into the canonical query used both as the cache key and as the upstream query
 * (PLAN.md, Section 3.4).
 *
 * <p>Values and the relative order of repeated parameters are preserved exactly. The only changes are a stable
 * sort by parameter <em>name</em>, dropping empty {@code &&} segments, and canonical percent-encoding.
 */
public final class QueryNormalizer {

    /** One decoded parameter. A {@code null} value is the bare {@code flag} form, distinct from {@code flag=}. */
    public record QueryParam(String name, String value) {
    }

    private final int maxQueryLength;
    private final int maxQueryParams;

    public QueryNormalizer(int maxQueryLength, int maxQueryParams) {
        if (maxQueryLength < 1 || maxQueryParams < 1) {
            throw new IllegalArgumentException("limits must be >= 1");
        }
        this.maxQueryLength = maxQueryLength;
        this.maxQueryParams = maxQueryParams;
    }

    /**
     * Returns the canonical form of {@code rawQuery} (the raw, still-encoded query from the request URI).
     *
     * @throws BadRequestException 414 if the query is too long; 400 if it is malformed, has too many parameters,
     *                             or lacks a required parameter of {@code route}
     */
    public String canonicalize(String rawQuery, Route route) throws BadRequestException {
        List<QueryParam> params = parse(rawQuery);
        params.sort(Comparator.comparing(QueryParam::name)); // List.sort is stable: same-name order is kept.

        for (String required : new TreeSet<>(route.requiredParams())) {
            boolean present = params.stream().anyMatch(p -> p.name().equals(required) && p.value() != null);
            if (!present) {
                throw new BadRequestException(400, "Missing required parameter: " + required);
            }
        }

        StringJoiner joined = new StringJoiner("&");
        for (QueryParam p : params) {
            String name = URLEncoder.encode(p.name(), StandardCharsets.UTF_8);
            joined.add(p.value() == null ? name : name + "=" + URLEncoder.encode(p.value(), StandardCharsets.UTF_8));
        }
        return joined.toString();
    }

    private List<QueryParam> parse(String rawQuery) throws BadRequestException {
        List<QueryParam> params = new ArrayList<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        if (rawQuery.length() > maxQueryLength) {
            throw new BadRequestException(414, "Query string is longer than " + maxQueryLength + " characters");
        }
        for (String segment : rawQuery.split("&", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (params.size() == maxQueryParams) {
                throw new BadRequestException(400, "More than " + maxQueryParams + " query parameters");
            }
            int eq = segment.indexOf('=');
            String name = decode(eq < 0 ? segment : segment.substring(0, eq));
            String value = eq < 0 ? null : decode(segment.substring(eq + 1));
            params.add(new QueryParam(name, value));
        }
        return params;
    }

    /**
     * Form-decodes {@code s} ({@code +} is a space, {@code %XX} is a byte). Unlike {@link java.net.URLDecoder},
     * escaped bytes that are not valid UTF-8 are rejected instead of being replaced with U+FFFD, which would let
     * different queries (such as {@code %FF} and {@code %FE}) share one cache key.
     */
    static String decode(String s) throws BadRequestException {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '+') {
                out.append(' ');
                i++;
            } else if (c == '%') {
                // Collect a run of consecutive %XX escapes and decode the bytes together.
                ByteBuffer bytes = ByteBuffer.allocate((s.length() - i) / 3 + 1);
                while (i < s.length() && s.charAt(i) == '%') {
                    if (i + 2 >= s.length()) {
                        throw malformed();
                    }
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) {
                        throw malformed();
                    }
                    bytes.put((byte) ((hi << 4) | lo));
                    i += 3;
                }
                bytes.flip();
                try {
                    out.append(StandardCharsets.UTF_8.newDecoder().decode(bytes)); // newDecoder() reports errors.
                } catch (CharacterCodingException e) {
                    throw new BadRequestException(400, "Query contains percent-encoded bytes that are not valid UTF-8");
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static BadRequestException malformed() {
        return new BadRequestException(400, "Malformed percent-encoding in query");
    }
}
