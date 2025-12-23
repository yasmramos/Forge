package io.github.yasmramos.forge.performance;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple performance tests for Forge Build System
 */
class ForgePerformanceTest {
    
    private ForgeEngine forgeEngine;
    private ProjectConfig mockConfig;
    
    @BeforeEach
    void setUp() {
        mockConfig = createMockConfig();
        forgeEngine = new ForgeEngine(mockConfig);
    }
    
    @Test
    void testBasicBuildPerformance() {
        long startTime = System.currentTimeMillis();
        
        // Run a basic build
        forgeEngine.build();
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Basic assertion that build completes in reasonable time (less than 10 seconds)
        assertTrue(duration < 10000, "Build should complete within 10 seconds, took: " + duration + "ms");
    }
    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("performance-test");
        config.setVersion("1.0.0");
        
        // Use non-existent paths to avoid scanning actual project files
        java.util.List<String> sourcePaths = new ArrayList<>();
        sourcePaths.add("/non/existent/path");
        sourcePaths.add("/another/non/existent/path");
        config.setSourcePaths(sourcePaths);
        
        return config;
    }
}