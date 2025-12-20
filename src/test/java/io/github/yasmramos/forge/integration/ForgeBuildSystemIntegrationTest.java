package io.github.yasmramos.forge.integration;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple integration tests for the Forge Build System
 */
class ForgeBuildSystemIntegrationTest {
    
    private ForgeEngine forgeEngine;
    private ProjectConfig mockConfig;
    
    @BeforeEach
    void setUp() {
        mockConfig = createMockConfig();
        forgeEngine = new ForgeEngine(mockConfig);
    }
    
    @Test
    void testCompleteBuildWorkflow() {
        // Test that the complete build workflow can be executed
        assertNotNull(forgeEngine);
        
        // Execute full build
        var result = forgeEngine.build();
        assertNotNull(result);
        
        // Test incremental build
        var incrementalResult = forgeEngine.buildIncremental();
        assertNotNull(incrementalResult);
    }
    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("integration-test");
        config.setVersion("1.0.0");
        config.setSourcePaths(new ArrayList<>());
        return config;
    }
}