package dev.nimbus.proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nimbus.config.NimbusConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryNormalizerTest {

    private static final NimbusConfig CONFIG = NimbusConfig.defaults();
    private static final Route FORECAST = CONFIG.forecastRoute();
    private static final Route SEARCH = CONFIG.geocodingRoute();
    private static final String LOC = "latitude=52.52437&longitude=13.41053";

    private final QueryNormalizer normalizer =
            new QueryNormalizer(CONFIG.maxQueryLength(), CONFIG.maxQueryParams());

    private String forecast(String query) throws BadRequestException {
        return normalizer.canonicalize(query, FORECAST);
    }

    @ParameterizedTest(name = "{0} == {1}")
    @CsvSource(delimiter = '|', value = {
            "latitude=52.52437&longitude=13.41053&current=temperature_2m | current=temperature_2m&longitude=13.41053&latitude=52.52437",
            "latitude=1&longitude=2&current=a%2Cb | latitude=1&longitude=2&current=a,b",
            "latitude=1&&longitude=2 | latitude=1&longitude=2",
            "&latitude=1&longitude=2& | latitude=1&longitude=2",
            "latitude=1&longitude=2&timezone=Europe%2FBerlin | timezone=Europe/Berlin&latitude=1&longitude=2",
            "latitude=1&longitude=2&x=%7e | latitude=1&longitude=2&x=~",
    })
    void equalKeys(String a, String b) throws BadRequestException {
        assertEquals(forecast(a), forecast(b));
    }

    @ParameterizedTest(name = "{0} != {1}")
    @CsvSource(delimiter = '|', value = {
            LOC + "&current=a,b | " + LOC + "&current=b,a",
            LOC + "&current=a&current=b | " + LOC + "&current=b&current=a",
            "latitude=1&latitude=2&longitude=3&longitude=4 | latitude=1&latitude=2&longitude=4&longitude=3",
            "latitude=52.52&longitude=13.41 | latitude=52.520&longitude=13.41",
            LOC + "&flag | " + LOC + "&flag=",
            LOC + "&x=a+b | " + LOC + "&x=a%2Bb",
            LOC + "&current=a,b | " + LOC + "&current=a&current=b",
    })
    void differentForecastKeys(String a, String b) throws BadRequestException {
        assertNotEquals(forecast(a), forecast(b));
    }

    @ParameterizedTest(name = "{0} != {1}")
    @CsvSource(delimiter = '|', value = {
            "name=Berlin | name=berlin",
            "name=Berlin | name=Berlin&count=10",
    })
    void differentSearchKeys(String a, String b) throws BadRequestException {
        assertNotEquals(normalizer.canonicalize(a, SEARCH), normalizer.canonicalize(b, SEARCH));
    }

    @Test
    void canonicalFormIsSortedByNameAndReencoded() throws BadRequestException {
        assertEquals("current=temperature_2m&current=wind_speed_10m&latitude=52.52437&longitude=13.41053",
                forecast("longitude=13.41053&current=temperature_2m&latitude=52.52437&current=wind_speed_10m"));
        assertEquals("current=a%2Cb&flag&latitude=1&longitude=2&timezone=",
                forecast("timezone=&flag&current=a,b&latitude=1&longitude=2"));
    }

    @Test
    void plusAndEncodedPlusStayDistinct() throws BadRequestException {
        assertEquals("name=S%C3%A3o+Paulo", normalizer.canonicalize("name=S%C3%A3o+Paulo", SEARCH));
        assertEquals("name=a%2Bb", normalizer.canonicalize("name=a%2Bb", SEARCH));
    }

    @Test
    void unicodeRoundTrips() throws BadRequestException {
        for (String city : List.of("Zürich", "São Paulo", "東京")) {
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            assertEquals("name=" + encoded, normalizer.canonicalize("name=" + encoded, SEARCH));
            assertEquals(city, QueryNormalizer.decode(encoded));
        }
        // A space written as %20 and as + is the same character.
        assertEquals(normalizer.canonicalize("name=S%C3%A3o%20Paulo", SEARCH),
                normalizer.canonicalize("name=S%C3%A3o+Paulo", SEARCH));
    }

    @Test
    void missingRequiredParameterIs400() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> forecast("latitude=1"));
        assertEquals(400, e.status());
        assertEquals("Missing required parameter: longitude", e.reason());
        assertEquals(400, assertThrows(BadRequestException.class, () -> forecast(null)).status());
        assertEquals(400, assertThrows(BadRequestException.class, () -> forecast("")).status());
        // The flag form has no value, so it doesn't satisfy a required parameter; an empty value does.
        assertEquals(400, assertThrows(BadRequestException.class, () -> normalizer.canonicalize("name", SEARCH))
                .status());
        assertEquals("name=", assertDoesNotThrow(() -> normalizer.canonicalize("name=", SEARCH)));
    }

    @ParameterizedTest
    @CsvSource({"%zz", "%", "%2", "a%2", "%FF", "%C3", "%C3%28"})
    void malformedEncodingIs400(String value) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> forecast(LOC + "&x=" + value));
        assertEquals(400, e.status());
        assertEquals(400, assertThrows(BadRequestException.class, () -> forecast(LOC + "&" + value + "=1")).status());
    }

    @Test
    void tooManyParametersIs400() throws BadRequestException {
        String hundred = LOC + IntStream.range(0, 98).mapToObj(i -> "&p" + i + "=1").collect(Collectors.joining());
        forecast(hundred);
        BadRequestException e = assertThrows(BadRequestException.class, () -> forecast(hundred + "&one=more"));
        assertEquals(400, e.status());
    }

    @Test
    void tooLongQueryIs414() throws BadRequestException {
        String base = LOC + "&x=";
        String exact = base + "a".repeat(CONFIG.maxQueryLength() - base.length());
        assertEquals(CONFIG.maxQueryLength(), exact.length());
        forecast(exact);
        BadRequestException e = assertThrows(BadRequestException.class, () -> forecast(exact + "a"));
        assertEquals(414, e.status());
    }

    /** Property: the stable sort keeps the relative order of every name's values. */
    @RepeatedTest(50)
    void sortKeepsSameNameOrder(RepetitionInfo info) throws BadRequestException {
        Random random = new Random(info.getCurrentRepetition());
        List<String> names = List.of("latitude", "longitude", "current", "hourly", "b", "a");
        List<String[]> params = new ArrayList<>();
        params.add(new String[] {"latitude", "0"});
        params.add(new String[] {"longitude", "0"});
        for (int i = 0; i < 1 + random.nextInt(40); i++) {
            params.add(new String[] {names.get(random.nextInt(names.size())), Integer.toString(random.nextInt(1000))});
        }
        Collections.shuffle(params, random);
        String query = params.stream().map(p -> p[0] + "=" + p[1]).collect(Collectors.joining("&"));

        String canonical = forecast(query);

        List<String[]> out = List.of(canonical.split("&")).stream().map(s -> s.split("=", 2)).toList();
        for (String name : names) {
            List<String> expected = params.stream().filter(p -> p[0].equals(name)).map(p -> p[1]).toList();
            List<String> actual = out.stream().filter(p -> p[0].equals(name)).map(p -> p[1]).toList();
            assertEquals(expected, actual, "values of " + name + " in " + query);
        }
        List<String> outNames = out.stream().map(p -> p[0]).toList();
        assertEquals(outNames.stream().sorted().toList(), outNames, "sorted by name");
    }
}
