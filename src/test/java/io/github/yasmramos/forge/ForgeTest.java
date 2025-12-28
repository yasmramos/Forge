package io.github.yasmramos.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Forge main class.
 * Tests basic instantiation and initialization.
 */
class ForgeTest {

    @Test
    void testForgeInstantiation() {
        Forge forge = new Forge();
        assertNotNull(forge, "Forge should be instantiable");
    }

    @Test
    void testForgeHasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            new Forge();
        }, "Forge should have a working default constructor");
    }
}
