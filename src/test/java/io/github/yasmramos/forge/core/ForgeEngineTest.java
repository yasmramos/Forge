package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.core.ForgeEngine.BuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for ForgeEngine
 */
class ForgeEngineTest {

    /**
     * Isolated build output directory for the whole test class. Without it,
     * engine builds would write compiled classes into the shared
     * target/classes tree and race with Surefire's own class loading.
     */
    @TempDir
    static Path sharedBuildDir;

    private ForgeEngine forgeEngine;
    private ProjectConfig mockConfig;

    @BeforeEach
    void setUp() {
        mockConfig = createMockConfig();
        mockConfig.setOutputDirectory(sharedBuildDir.resolve("classes").toString());
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