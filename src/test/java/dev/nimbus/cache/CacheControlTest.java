package dev.nimbus.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CacheControlTest {

    private static CacheControl parse(String... lines) {
        return CacheControl.parse(List.of(lines));
    }

    @Test
    void noHeaderMeansNothingSet() {
        assertEquals(CacheControl.NONE, CacheControl.parse(List.of()));
        assertEquals(CacheControl.NONE, parse(""));
        assertEquals(CacheControl.NONE, parse(" , ,"));
    }

    @Test
    void everyDirective() {
        CacheControl cc = parse("no-store, no-cache, private, must-revalidate, proxy-revalidate, max-age=60, s-maxage=30");
        assertTrue(cc.noStore());
        assertTrue(cc.noCache());
        assertTrue(cc.isPrivate());
        assertTrue(cc.mustRevalidate());
        assertTrue(cc.proxyRevalidate());
        assertEquals(OptionalLong.of(60), cc.maxAge());
        assertEquals(OptionalLong.of(30), cc.sMaxAge());
    }

    @Test
    void eachFlagAlone() {
        assertTrue(parse("no-store").noStore());
        assertTrue(parse("no-cache").noCache());
        assertTrue(parse("private").isPrivate());
        assertTrue(parse("must-revalidate").mustRevalidate());
        assertTrue(parse("proxy-revalidate").proxyRevalidate());
        CacheControl publicOnly = parse("public");
        assertFalse(publicOnly.noStore() || publicOnly.noCache() || publicOnly.isPrivate()
                || publicOnly.mustRevalidate() || publicOnly.proxyRevalidate());
        assertEquals(OptionalLong.empty(), publicOnly.maxAge());
    }

    @Test
    void namesAreCaseInsensitive() {
        CacheControl cc = parse("No-Store, MAX-AGE=5, Must-Revalidate");
        assertTrue(cc.noStore());
        assertTrue(cc.mustRevalidate());
        assertEquals(OptionalLong.of(5), cc.maxAge());
    }

    @Test
    void multipleHeaderLinesAreMerged() {
        CacheControl cc = parse("public", "max-age=86400", "no-cache");
        assertEquals(OptionalLong.of(86400), cc.maxAge());
        assertTrue(cc.noCache());
    }

    @Test
    void quotedValuesAreUnquoted() {
        assertEquals(OptionalLong.of(60), parse("max-age=\"60\"").maxAge());
        assertEquals(OptionalLong.of(60), parse("max-age = 60").maxAge());
    }

    @Test
    void qualifiedFormsCountAsUnqualified() {
        CacheControl cc = parse("no-cache=\"Set-Cookie, X-Foo\", private=\"x\", max-age=10");
        assertTrue(cc.noCache());
        assertTrue(cc.isPrivate());
        assertEquals(OptionalLong.of(10), cc.maxAge(), "the comma inside quotes did not split the list");
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-age=abc", "max-age=-5", "max-age", "max-age=", "max-age=1.5", "max-age=\"\""})
    void malformedOrNegativeMaxAgeIsZero(String header) {
        assertEquals(OptionalLong.of(0), parse(header).maxAge());
    }

    @Test
    void malformedSMaxAgeIsZero() {
        assertEquals(OptionalLong.of(0), parse("s-maxage=-1").sMaxAge());
    }

    @Test
    void hugeValuesAreCappedAt2To31() {
        assertEquals(OptionalLong.of(2_147_483_648L), parse("max-age=99999999999999999999").maxAge());
        assertEquals(OptionalLong.of(2_147_483_648L), parse("max-age=3000000000").maxAge());
        assertEquals(OptionalLong.of(2_000_000_000L), parse("max-age=2000000000").maxAge());
    }

    @Test
    void firstOccurrenceWins() {
        assertEquals(OptionalLong.of(60), parse("max-age=60, max-age=600").maxAge());
        assertEquals(OptionalLong.of(60), parse("max-age=60", "max-age=600").maxAge());
    }

    @Test
    void unknownDirectivesAreIgnored() {
        CacheControl cc = parse("public, immutable, stale-while-revalidate=30, stale-if-error=60, max-age=5");
        assertEquals(OptionalLong.of(5), cc.maxAge());
        assertFalse(cc.noCache());
    }
}
