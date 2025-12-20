package io.github.yasmramos.forge.validation;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple validation tests for Forge Build System
 */
class ForgeDemoProjectValidationTest {
    
    @Test
    void testBasicValidation() {
        // Basic test that the system can be instantiated and used
        ProjectConfig config = createBasicConfig();
        ForgeEngine engine = new ForgeEngine(config);
        
        assertNotNull(engine);
        
        // Test that build works
        var result = engine.build();
        assertNotNull(result);
    }
    
    private ProjectConfig createBasicConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("validation-test");
        config.setVersion("1.0.0");
        config.setSourcePaths(new ArrayList<>());
        return config;
    }
}