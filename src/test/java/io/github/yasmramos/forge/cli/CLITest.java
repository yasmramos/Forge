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
}
