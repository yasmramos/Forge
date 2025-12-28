package io.github.yasmramos.forge.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ConfigReader class.
 * Tests basic instantiation and structure.
 */
class ConfigReaderTest {

    @Test
    void testConfigReaderInstantiation() {
        ConfigReader reader = new ConfigReader();
        assertNotNull(reader, "ConfigReader should be instantiable");
    }

    @Test
    void testConfigReaderHasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            new ConfigReader();
        }, "ConfigReader should have a working default constructor");
    }
}
