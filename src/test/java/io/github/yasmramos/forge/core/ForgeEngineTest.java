package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.core.ForgeEngine.BuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for ForgeEngine
 */
class ForgeEngineTest {
    
    private ForgeEngine forgeEngine;
    private ProjectConfig mockConfig;
    
    @BeforeEach
    void setUp() {
        mockConfig = createMockConfig();
        forgeEngine = new ForgeEngine(mockConfig);
    }
    
    @Test
    void testForgeEngineCreation() {
        assertNotNull(forgeEngine);
    }
    
    @Test
    void testBuildMethodExists() {
        // Test that the build method exists and returns a BuildResult
        BuildResult result = forgeEngine.build();
        assertNotNull(result);
    }
    
    @Test
    void testBuildIncrementalMethodExists() {
        // Test that the buildIncremental method exists and returns a BuildResult
        BuildResult result = forgeEngine.buildIncremental();
        assertNotNull(result);
    }
    
    @Test
    void testCleanMethodExists() {
        // Test that the clean method exists and can be called
        assertDoesNotThrow(() -> forgeEngine.clean());
    }
    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("test-project");
        config.setVersion("1.0.0");
        config.setSourcePaths(new ArrayList<>());
        return config;
    }
}