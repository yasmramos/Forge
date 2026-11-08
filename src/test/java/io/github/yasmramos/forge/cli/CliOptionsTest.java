package io.github.yasmramos.forge.cli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for global CLI flag parsing (AGENT.md sections 10 and 10bis). */
class CliOptionsTest {

    @Test
    void stripsGlobalFlagsFromCommandArguments() {
        CliOptions options = CliOptions.parse(
            new String[] {"build", "graph", "--format=json", "--debug"});
        assertTrue(options.isJson());
        assertTrue(options.isDebug());
        assertEquals(Arrays.asList("build", "graph"), options.rest());
    }

    @Test
    void defaultsAreOffAndArgumentsUntouched() {
        CliOptions options = CliOptions.parse(new String[] {"run", "--", "-x", "1"});
        assertFalse(options.isJson());
        assertFalse(options.isDebug());
        // "--" and program arguments must survive flag stripping untouched.
        assertEquals(Arrays.asList("run", "--", "-x", "1"), options.rest());
    }

    @Test
    void handlesNullAndEmptyArguments() {
        assertFalse(CliOptions.parse(null).isJson());
        assertEquals(0, CliOptions.parse(new String[] {}).rest().size());
    }

    @Test
    void jsonAliasIsAccepted() {
        assertTrue(CliOptions.parse(new String[] {"build", "--json"}).isJson());
    }

    @Test
    void variantFlagIsExtractedFromArguments() {
        CliOptions options = CliOptions.parse(
            new String[] {"build", "--variant=prod"}, null);
        assertEquals("prod", options.getVariant());
        assertEquals(Arrays.asList("build"), options.rest());
    }

    @Test
    void variantFallsBackToEnvironmentWhenFlagAbsent() {
        assertEquals("benchmark", CliOptions.parse(new String[] {"build"}, "benchmark").getVariant());
        // The flag wins over the environment (explicit over implicit, section 8).
        assertEquals("prod",
            CliOptions.parse(new String[] {"build", "--variant=prod"}, "benchmark").getVariant());
        assertEquals(null, CliOptions.parse(new String[] {"build"}, null).getVariant());
    }

    @Test
    void programArgumentsAfterDoubleDashAreNeverTreatedAsFlags() {
        CliOptions options = CliOptions.parse(
            new String[] {"run", "--", "--variant=prod", "--debug"}, null);
        // The program's own flags must reach it verbatim, never be swallowed.
        assertEquals(null, options.getVariant());
        assertFalse(options.isDebug());
        assertEquals(Arrays.asList("run", "--", "--variant=prod", "--debug"), options.rest());
    }
}
