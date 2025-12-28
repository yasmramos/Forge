package io.github.yasmramos.forge.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for model classes.
 * Tests data structures and their operations.
 */
class ModelClassesTest {

    @Test
    void testProjectConfigDefaultValues() {
        ProjectConfig config = new ProjectConfig();

        // Test default values
        assertNull(config.getName());
        assertNull(config.getVersion());
        assertNull(config.getSourceDirectory());
        assertNotNull(config.getSourcePaths());
        assertTrue(config.getSourcePaths().isEmpty());
    }

    @Test
    void testProjectConfigSettersAndGetters() {
        ProjectConfig config = new ProjectConfig();

        // Test setters and getters
        config.setName("test-project");
        assertEquals("test-project", config.getName());

        config.setVersion("1.0.0");
        assertEquals("1.0.0", config.getVersion());

        config.setSourceDirectory("src/main/java");
        assertEquals("src/main/java", config.getSourceDirectory());

        config.setOutputDirectory("target/classes");
        assertEquals("target/classes", config.getOutputDirectory());
    }

    @Test
    void testProjectConfigSourcePaths() {
        ProjectConfig config = new ProjectConfig();

        // Test source paths
        List<String> paths = Arrays.asList("/path/1", "/path/2", "/path/3");
        config.setSourcePaths(paths);

        assertNotNull(config.getSourcePaths());
        assertEquals(3, config.getSourcePaths().size());
        assertEquals("/path/1", config.getSourcePaths().get(0));
        assertEquals("/path/2", config.getSourcePaths().get(1));
        assertEquals("/path/3", config.getSourcePaths().get(2));
    }

    @Test
    void testProjectConfigDependencies() {
        ProjectConfig config = new ProjectConfig();

        // Test dependencies map
        assertNull(config.getDependencies());

        config.setDependencies(null);
        assertNull(config.getDependencies());
    }

    @Test
    void testProjectAnalysisCreation() {
        ProjectAnalysis analysis = new ProjectAnalysis();

        assertNotNull(analysis, "ProjectAnalysis should be created");
        assertTrue(analysis.getSourceFiles().isEmpty());
        assertEquals(0, analysis.getTotalLinesOfCode());
        assertEquals(0.0, analysis.getComplexityScore());
        assertEquals(0, analysis.getEstimatedBuildTime());
    }

    @Test
    void testProjectAnalysisSetters() {
        ProjectAnalysis analysis = new ProjectAnalysis();

        // Test setters
        analysis.setTotalLinesOfCode(1000);
        assertEquals(1000, analysis.getTotalLinesOfCode());

        analysis.setComplexityScore(85.5);
        assertEquals(85.5, analysis.getComplexityScore());

        analysis.setEstimatedBuildTime(5000);
        assertEquals(5000, analysis.getEstimatedBuildTime());
    }

    @Test
    void testCompilationResultCreation() {
        CompilationResult result = new CompilationResult(true, 10, 10);

        assertNotNull(result, "CompilationResult should be created");
        assertTrue(result.isSuccess());
        assertEquals(10, result.getTotalFiles());
        assertEquals(10, result.getCompiledFiles());
    }

    @Test
    void testCompilationResultFailure() {
        CompilationResult result = new CompilationResult(false, 5, 3);

        assertFalse(result.isSuccess());
        assertEquals(5, result.getTotalFiles());
        assertEquals(3, result.getCompiledFiles());
    }

    @Test
    void testDependencyInfoCreation() {
        DependencyInfo info = new DependencyInfo("org.example", "1.0.0", DependencyType.MAVEN);

        assertNotNull(info, "DependencyInfo should be created");
        assertEquals("org.example", info.getName());
        assertEquals("1.0.0", info.getVersion());
        assertEquals(DependencyType.MAVEN, info.getType());
    }

    @Test
    void testDependencyTypeValues() {
        // Test dependency type enum values
        assertNotNull(DependencyType.MAVEN);
        assertNotNull(DependencyType.GRADLE);
        assertNotNull(DependencyType.NPM);
        assertNotNull(DependencyType.PIP);
        assertNotNull(DependencyType.GO_MOD);
        assertNotNull(DependencyType.CARGO);
        assertNotNull(DependencyType.LOCAL);
        assertNotNull(DependencyType.SYSTEM);
    }

    @Test
    void testPackageResultCreation() {
        PackageResult result = new PackageResult(true, "jar", 1);

        assertNotNull(result, "PackageResult should be created");
        assertTrue(result.isSuccess());
        assertEquals("jar", result.getType());
        assertEquals(1, result.getArtifacts());
    }

    @Test
    void testPackageResultSetters() {
        // PackageResult is immutable, test its properties
        PackageResult result = new PackageResult(true, "jar", 1);

        assertTrue(result.isSuccess());
        assertEquals("jar", result.getType());
        assertEquals(1, result.getArtifacts());
    }
}
