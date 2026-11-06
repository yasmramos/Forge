package io.github.yasmramos.forge.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ForgeCLI class.
 * Tests basic instantiation and structure.
 */
class CLITest {

    @Test
    void testCLIInstantiation() {
        String[] args = {};
        ForgeCLI cli = new ForgeCLI(args);
        assertNotNull(cli, "CLI should be instantiable");
    }

    @Test
    void testCLIWithEmptyArgs() {
        String[] args = {};
        assertDoesNotThrow(() -> {
            new ForgeCLI(args);
        }, "CLI should handle empty arguments");
    }

    @Test
    void testCLIWithNullArgs() {
        assertDoesNotThrow(() -> {
            new ForgeCLI(null);
        }, "CLI should handle null arguments");
    }

    @Test
    void unknownCommandReturnsUsageErrorCode() {
        ForgeCLI cli = new ForgeCLI(new String[] {"frobnicate"});
        int exit = cli.execute(null);
        assertEquals(1, exit, "unknown command is a user/config error");
    }

    @Test
    void buildWithoutProjectFailsWithStructuredError() {
        // No engine: the command needs a project, so it must not NPE but
        // return a user-facing error code (AGENT.md 10bis).
        ForgeCLI cli = new ForgeCLI(new String[] {"build"});
        assertEquals(1, cli.execute(null));
    }

    @Test
    void helpSucceedsWithoutProject() {
        ForgeCLI cli = new ForgeCLI(new String[] {"help"});
        assertEquals(0, cli.execute(null));
    }

    @Test
    void badBuildTypeReturnsUsageError() {
        ForgeCLI cli = new ForgeCLI(new String[] {"build", "nonsense"});
        assertEquals(1, cli.execute(null));
    }
}
