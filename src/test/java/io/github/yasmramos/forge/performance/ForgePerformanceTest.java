package io.github.yasmramos.forge.performance;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.BuildAnalyzer;
import io.github.yasmramos.forge.core.DependencyResolver;
import io.github.yasmramos.forge.core.Compiler;
import io.github.yasmramos.forge.cache.ForgeCache;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests and benchmarking for Forge Build System
 */
class ForgePerformanceTest {
    
    private ForgeEngine forgeEngine;
    private BuildAnalyzer buildAnalyzer;
    private DependencyResolver dependencyResolver;
    private Compiler compiler;
    private ForgeCache cache;
    
    @BeforeEach
    void setUp() {
        cache = new ForgeCache();
        buildAnalyzer = new BuildAnalyzer();
        dependencyResolver = new DependencyResolver();
        compiler = new Compiler();
        
        forgeEngine = new ForgeEngine(buildAnalyzer, dependencyResolver, compiler, cache);
    }
    
    @Test
    void testProjectAnalysisPerformance(@TempDir Path tempDir) throws Exception {
        // Create a project with multiple files
        createLargeTestProject(tempDir, 50); // 50 files
        
        ProjectConfig config = createTestConfig(tempDir);
        
        // Measure analysis time
        long startTime = System.nanoTime();
        io.github.yasmramos.forge.model.ProjectAnalysis analysis = buildAnalyzer.analyzeProject(config);
        long endTime = System.nanoTime();
        
        long analysisTimeMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(analysis);
        assertTrue(analysisTimeMs < 5000, "Analysis took too long: " + analysisTimeMs + "ms");
        assertTrue(analysis.getSourceFiles().size() >= 50, "Should find all source files");
        
        System.out.println("📊 Project Analysis Performance:");
        System.out.println("   Files analyzed: " + analysis.getSourceFiles().size());
        System.out.println("   Time taken: " + analysisTimeMs + "ms");
        System.out.println("   Average per file: " + (analysisTimeMs / 50.0) + "ms");
    }
    
    @Test
    void testDependencyResolutionPerformance(@TempDir Path tempDir) throws Exception {
        createSmallTestProject(tempDir);
        ProjectConfig config = createTestConfig(tempDir);
        
        io.github.yasmramos.forge.model.ProjectAnalysis analysis = buildAnalyzer.analyzeProject(config);
        
        // Measure dependency resolution time
        long startTime = System.nanoTime();
        io.github.yasmramos.forge.model.DependencyResolution resolution = dependencyResolver.resolve(
            config.getDependencies(), analysis
        );
        long endTime = System.nanoTime();
        
        long resolutionTimeMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(resolution);
        // Should complete within reasonable time (may have network delays in tests)
        assertTrue(resolutionTimeMs < 30000, "Dependency resolution took too long: " + resolutionTimeMs + "ms");
        
        System.out.println("🔗 Dependency Resolution Performance:");
        System.out.println("   Dependencies: " + config.getDependencies().size());
        System.out.println("   Time taken: " + resolutionTimeMs + "ms");
        System.out.println("   Successful: " + resolution.getSuccessCount());
        System.out.println("   Errors: " + resolution.getErrorCount());
    }
    
    @Test
    void testIncrementalBuildPerformance(@TempDir Path tempDir) throws Exception {
        createMediumTestProject(tempDir);
        ProjectConfig config = createTestConfig(tempDir);
        
        // First full build
        long fullBuildStart = System.nanoTime();
        ForgeEngine.BuildResult fullBuild = forgeEngine.buildFullProject(config);
        long fullBuildEnd = System.nanoTime();
        long fullBuildTimeMs = (fullBuildEnd - fullBuildStart) / 1_000_000;
        
        assertNotNull(fullBuild);
        
        // Modify a few files for incremental build
        Path file1 = tempDir.resolve("src/main/java/com/example/File1.java");
        Path file2 = tempDir.resolve("src/main/java/com/example/File2.java");
        
        if (Files.exists(file1)) {
            String content1 = Files.readString(file1) + "\n// Incremental test modification";
            Files.writeString(file1, content1);
        }
        
        if (Files.exists(file2)) {
            String content2 = Files.readString(file2) + "\n// Incremental test modification 2";
            Files.writeString(file2, content2);
        }
        
        // Incremental build
        long incrementalBuildStart = System.nanoTime();
        ForgeEngine.BuildResult incrementalBuild = forgeEngine.buildIncremental(config);
        long incrementalBuildEnd = System.nanoTime();
        long incrementalBuildTimeMs = (incrementalBuildEnd - incrementalBuildStart) / 1_000_000;
        
        assertNotNull(incrementalBuild);
        
        System.out.println("⚡ Incremental Build Performance:");
        System.out.println("   Full build time: " + fullBuildTimeMs + "ms");
        System.out.println("   Incremental build time: " + incrementalBuildTimeMs + "ms");
        System.out.println("   Speedup: " + (fullBuildTimeMs > 0 ? (double)fullBuildTimeMs / incrementalBuildTimeMs : "N/A"));
        
        // Incremental should be faster or at least not significantly slower
        assertTrue(incrementalBuildTimeMs <= fullBuildTimeMs * 1.5, 
            "Incremental build should be faster: " + incrementalBuildTimeMs + "ms vs " + fullBuildTimeMs + "ms");
    }
    
    @Test
    void testCachePerformance(@TempDir Path tempDir) throws Exception {
        // Test cache operations performance
        int numOperations = 1000;
        
        long startTime = System.nanoTime();
        
        // Put operations
        for (int i = 0; i < numOperations; i++) {
            cache.put("key-" + i, "value-" + i);
        }
        
        // Get operations
        for (int i = 0; i < numOperations; i++) {
            String value = cache.get("key-" + i, String.class);
            assertNotNull(value);
        }
        
        long endTime = System.nanoTime();
        long totalTimeMs = (endTime - startTime) / 1_000_000;
        
        System.out.println("🚀 Cache Performance:");
        System.out.println("   Operations: " + (numOperations * 2));
        System.out.println("   Total time: " + totalTimeMs + "ms");
        System.out.println("   Operations per second: " + (numOperations * 2000 / totalTimeMs));
        
        assertTrue(totalTimeMs < 1000, "Cache operations took too long: " + totalTimeMs + "ms");
    }
    
    @Test
    void testMemoryUsage(@TempDir Path tempDir) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        
        // Force garbage collection
        System.gc();
        Thread.sleep(100);
        
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        
        // Create and process a large project
        createLargeTestProject(tempDir, 100);
        ProjectConfig config = createTestConfig(tempDir);
        
        io.github.yasmramos.forge.model.ProjectAnalysis analysis = buildAnalyzer.analyzeProject(config);
        
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        
        System.out.println("💾 Memory Usage:");
        System.out.println("   Memory before: " + (memoryBefore / 1024 / 1024) + "MB");
        System.out.println("   Memory after: " + (memoryAfter / 1024 / 1024) + "MB");
        System.out.println("   Memory used: " + (memoryUsed / 1024 / 1024) + "MB");
        System.out.println("   Memory per file: " + (memoryUsed / 100 / 1024) + "KB");
        
        // Should use reasonable amount of memory (less than 50MB for 100 files)
        assertTrue(memoryUsed < 50 * 1024 * 1024, "Memory usage too high: " + (memoryUsed / 1024 / 1024) + "MB");
    }
    
    @Test
    void testConcurrentBuildPerformance(@TempDir Path tempDir) throws Exception {
        createMediumTestProject(tempDir);
        ProjectConfig config = createTestConfig(tempDir);
        
        // Test multiple concurrent builds
        int numConcurrentBuilds = 3;
        long[] buildTimes = new long[numConcurrentBuilds];
        
        Thread[] threads = new Thread[numConcurrentBuilds];
        
        for (int i = 0; i < numConcurrentBuilds; i++) {
            final int buildIndex = i;
            threads[i] = new Thread(() -> {
                long start = System.nanoTime();
                ForgeEngine.BuildResult result = forgeEngine.buildFullProject(config);
                long end = System.nanoTime();
                buildTimes[buildIndex] = (end - start) / 1_000_000;
                assertNotNull(result);
            });
        }
        
        // Start all builds concurrently
        long concurrentStart = System.nanoTime();
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all to complete
        for (Thread thread : threads) {
            thread.join();
        }
        long concurrentEnd = System.nanoTime();
        
        long totalConcurrentTime = (concurrentEnd - concurrentStart) / 1_000_000;
        
        System.out.println("🔄 Concurrent Build Performance:");
        System.out.println("   Concurrent builds: " + numConcurrentBuilds);
        System.out.println("   Total concurrent time: " + totalConcurrentTime + "ms");
        
        for (int i = 0; i < numConcurrentBuilds; i++) {
            System.out.println("   Build " + (i+1) + " time: " + buildTimes[i] + "ms");
        }
        
        assertTrue(totalConcurrentTime > 0, "Concurrent builds should take some time");
    }
    
    private void createLargeTestProject(Path projectDir, int numFiles) throws Exception {
        Path srcDir = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        
        for (int i = 0; i < numFiles; i++) {
            Path file = srcDir.resolve("LargeClass" + i + ".java");
            String content = """
                package com.example;
                
                public class LargeClass""" + i + """ {
                    public void method1() {
                        for (int j = 0; j < 100; j++) {
                            if (j % 2 == 0) {
                                System.out.println("Iteration: " + j);
                            }
                        }
                    }
                    
                    public void method2() {
                        int[] numbers = new int[50];
                        for (int k = 0; k < numbers.length; k++) {
                            numbers[k] = k * 2;
                        }
                    }
                    
                    public static void main(String[] args) {
                        LargeClass""" + i + """ instance = new LargeClass""" + i + """();
                        instance.method1();
                        instance.method2();
                    }
                }
                """;
            Files.writeString(file, content);
        }
    }
    
    private void createMediumTestProject(Path projectDir) throws Exception {
        createLargeTestProject(projectDir, 20);
    }
    
    private void createSmallTestProject(Path projectDir) throws Exception {
        createLargeTestProject(projectDir, 5);
    }
    
    private ProjectConfig createTestConfig(Path projectDir) {
        ProjectConfig config = new ProjectConfig();
        config.setName("performance-test");
        config.setVersion("1.0.0");
        config.setGroup("com.example");
        config.setArtifact("performance-test");
        
        List<String> sourceDirs = new ArrayList<>();
        sourceDirs.add(projectDir.resolve("src/main/java").toString());
        config.setSourceDirectories(sourceDirs);
        
        Map<String, Object> dependencies = new HashMap<>();
        dependencies.put("junit:junit", "4.13.2");
        config.setDependencies(dependencies);
        
        return config;
    }
}