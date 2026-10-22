package dev.nimbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            return Main.run(args, out, err);
        }
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void unknownModeReturns2AndPrintsUsage() {
        assertEquals(2, run("bogus"));
        assertTrue(err().contains("Unknown mode: bogus"), err());
        assertTrue(err().contains("Usage:"), err());
        assertEquals("", out());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void helpReturns0AndPrintsUsage(String flag) {
        assertEquals(0, run(flag));
        assertTrue(out().contains("Usage:"), out());
        for (String mode : Main.MODES) {
            assertTrue(out().contains(mode), "usage lists mode " + mode);
        }
    }

    @Test
    void helpWinsOverUnknownMode() {
        assertEquals(0, run("bogus", "--help"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"server", "gui", "all", "bench", "fake-upstream"})
    void eachModeIsAPlaceholder(String mode) {
        assertEquals(0, run(mode));
        assertEquals(mode + ": not implemented yet" + System.lineSeparator(), out());
    }

    @Test
    void noArgumentsRunsDefaultMode() {
        assertEquals(0, run());
        assertTrue(out().startsWith("all: not implemented yet"), out());
    }

    @Test
    void keyValueFlagsAreAccepted() {
        assertEquals(0, run("server", "--port=0", "--nimbus.cache.maxBytes=1024"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--verbose", "-x", "--=1"})
    void malformedOptionsReturn2(String option) {
        assertEquals(2, run("server", option));
        assertTrue(err().contains("Unknown option: " + option), err());
    }

    @Test
    void moreThanOneModeReturns2() {
        assertEquals(2, run("server", "gui"));
    }
}
