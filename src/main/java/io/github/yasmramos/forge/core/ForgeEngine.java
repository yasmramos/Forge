package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.cache.ForgeCache;
import io.github.yasmramos.forge.utils.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Core Build Engine for Forge Build System
 * Handles compilation, dependency resolution, and build orchestration
 */
public class ForgeEngine {
    
    private final Logger logger = Logger.getLogger(ForgeEngine.class);
    private final ProjectConfig config;
    private final ForgeCache cache;
    private final ExecutorService executor;
    private final DependencyResolver dependencyResolver;
    private final Compiler compiler;
    private final BuildAnalyzer analyzer;
    
    public ForgeEngine(ProjectConfig config) {
        this.config = config;
        this.cache = new ForgeCache();
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, config.getBuildSettings().getThreads())
        );
        this.dependencyResolver = new DependencyResolver();
        this.compiler = new Compiler();
        this.analyzer = new BuildAnalyzer();
    }
    
    /**
     * Execute full build process
     */
    public BuildResult build() {
        logger.info("🔨 Starting Forge build for project: " + config.getName());
        
        try {
            // Phase 1: Analyze project structure
            logger.info("📊 Analyzing project structure...");
            ProjectAnalysis analysis = analyzer.analyzeProject(config);
            
            // Phase 2: Resolve dependencies
            logger.info("🔗 Resolving dependencies...");
            DependencyResolution dependencyResult = dependencyResolver.resolve(
                config.getDependencies(), analysis
            );
            
            // Phase 3: Compile sources
            logger.info("⚡ Compiling sources...");
            CompilationResult compilationResult = compileSources(analysis, dependencyResult);
            
            // Phase 4: Package artifacts
            logger.info("📦 Packaging artifacts...");
            PackageResult packageResult = packageArtifacts(compilationResult);
            
            // Phase 5: Run tests if enabled
            if (config.getBuildSettings().isParallel()) {
                logger.info("🧪 Running tests...");
                TestResult testResult = runTests(packageResult);
                
                return new BuildResult(true, packageResult, testResult);
            }
            
            return new BuildResult(true, packageResult, null);
            
        } catch (Exception e) {
            logger.error("Build failed", e);
            return new BuildResult(false, null, null);
        } finally {
            shutdown();
        }
    }
    
    /**
     * Execute incremental build
     */
    public BuildResult buildIncremental() {
        logger.info("🔄 Starting incremental build...");
        
        try {
            // Analyze changed files
            ProjectAnalysis analysis = analyzer.analyzeChanges(config);
            
            if (!analysis.hasChanges()) {
                logger.info("✅ No changes detected, build skipped");
                return new BuildResult(true, null, null);
            }
            
            // Incremental compilation
            CompilationResult compilationResult = compiler.compileIncremental(
                analysis.getChangedSources(), 
                cache
            );
            
            return new BuildResult(true, compilationResult, null);
            
        } catch (Exception e) {
            logger.error("Incremental build failed", e);
            return new BuildResult(false, null, null);
        }
    }
    
    /**
     * Clean build artifacts
     */
    public void clean() {
        logger.info("🧹 Cleaning build artifacts...");
        
        try {
            if (config.getOutputDirectory() != null) {
                Path outputPath = Paths.get(config.getOutputDirectory());
                if (Files.exists(outputPath)) {
                    Files.walk(outputPath)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                logger.warn("Failed to delete: " + path, e);
                            }
                        });
                }
            }
            
            cache.clear();
            logger.info("✅ Clean completed");
            
        } catch (Exception e) {
            logger.error("Clean failed", e);
        }
    }
    
    private CompilationResult compileSources(ProjectAnalysis analysis, 
                                           DependencyResolution dependencyResult) {
        List<Path> sourceFiles = analysis.getSourceFiles();
        
        if (config.getBuildSettings().isParallel()) {
            return compileInParallel(sourceFiles, dependencyResult);
        } else {
            return compileSequentially(sourceFiles, dependencyResult);
        }
    }
    
    private CompilationResult compileInParallel(List<Path> sourceFiles, 
                                              DependencyResolution dependencyResult) {
        CompletableFuture<CompilationResult>[] compilationTasks = sourceFiles.stream()
            .map(sourceFile -> CompletableFuture.supplyAsync(() -> {
                try {
                    return compiler.compile(sourceFile, dependencyResult, cache);
                } catch (Exception e) {
                    logger.error("Failed to compile: " + sourceFile, e);
                    return null;
                }
            }, executor))
            .toArray(CompletableFuture[]::new);
        
        CompletableFuture.allOf(compilationTasks).join();
        
        return new CompilationResult(true, sourceFiles.size(), compilationTasks.length);
    }
    
    private CompilationResult compileSequentially(List<Path> sourceFiles, 
                                                DependencyResolution dependencyResult) {
        int successCount = 0;
        
        for (Path sourceFile : sourceFiles) {
            try {
                compiler.compile(sourceFile, dependencyResult, cache);
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to compile: " + sourceFile, e);
            }
        }
        
        return new CompilationResult(successCount == sourceFiles.size(), 
                                   sourceFiles.size(), successCount);
    }
    
    private PackageResult packageArtifacts(CompilationResult compilationResult) {
        // TODO: Implement artifact packaging
        return new PackageResult(true, "jar", 0);
    }
    
    private TestResult runTests(PackageResult packageResult) {
        // TODO: Implement test execution
        return new TestResult(true, 0, 0);
    }
    
    private void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Result classes
    public static class BuildResult {
        private final boolean success;
        private final PackageResult packageResult;
        private final TestResult testResult;
        
        public BuildResult(boolean success, PackageResult packageResult, TestResult testResult) {
            this.success = success;
            this.packageResult = packageResult;
            this.testResult = testResult;
        }
        
        public boolean isSuccess() { return success; }
        public PackageResult getPackageResult() { return packageResult; }
        public TestResult getTestResult() { return testResult; }
    }
    
    public static class CompilationResult {
        private final boolean success;
        private final int totalFiles;
        private final int compiledFiles;
        
        public CompilationResult(boolean success, int totalFiles, int compiledFiles) {
            this.success = success;
            this.totalFiles = totalFiles;
            this.compiledFiles = compiledFiles;
        }
        
        public boolean isSuccess() { return success; }
        public int getTotalFiles() { return totalFiles; }
        public int getCompiledFiles() { return compiledFiles; }
    }
    
    public static class PackageResult {
        private final boolean success;
        private final String type;
        private final int artifacts;
        
        public PackageResult(boolean success, String type, int artifacts) {
            this.success = success;
            this.type = type;
            this.artifacts = artifacts;
        }
        
        public boolean isSuccess() { return success; }
        public String getType() { return type; }
        public int getArtifacts() { return artifacts; }
    }
    
    public static class TestResult {
        private final boolean success;
        private final int totalTests;
        private final int passedTests;
        
        public TestResult(boolean success, int totalTests, int passedTests) {
            this.success = success;
            this.totalTests = totalTests;
            this.passedTests = passedTests;
        }
        
        public boolean isSuccess() { return success; }
        public int getTotalTests() { return totalTests; }
        public int getPassedTests() { return passedTests; }
    }
}