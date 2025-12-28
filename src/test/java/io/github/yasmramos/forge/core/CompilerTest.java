package io.github.yasmramos.forge.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Compiler class.
 * Tests basic instantiation and structure.
 */
class CompilerTest {

    @Test
    void testCompilerInstantiation() {
        Compiler compiler = new Compiler();
        assertNotNull(compiler, "Compiler should be instantiable");
    }

    @Test
    void testCompilerHasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            new Compiler();
        }, "Compiler should have a working default constructor");
    }
}
