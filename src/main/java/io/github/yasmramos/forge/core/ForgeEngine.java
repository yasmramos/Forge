package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.model.PackageResult;
import io.github.yasmramos.forge.cache.ForgeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    
    private final Logger logger = LoggerFactory.getLogger(ForgeEngine.class);
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
            
            PackageResult packageResult = new PackageResult(
                compilationResult.isSuccess(), 
                "incremental", 
                compilationResult.getCompiledFiles()
            );
            return new BuildResult(true, packageResult, null);
            
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
        logger.info("📦 Packaging compiled artifacts...");
        
        try {
            // Create output directory
            Path outputDir = Paths.get("target", "forge-output");
            Files.createDirectories(outputDir);
            
            int artifactCount = 0;
            
            // Package main JAR
            Path mainJar = packageMainJar(outputDir);
            if (mainJar != null) {
                artifactCount++;
                logger.info("✅ Main JAR packaged: " + mainJar);
            }
            
            // Package additional artifacts (sources, javadoc, etc.)
            artifactCount += packageAdditionalArtifacts(outputDir);
            
            logger.info("📦 Packaging complete: " + artifactCount + " artifacts created");
            return new PackageResult(true, "jar", artifactCount);
            
        } catch (Exception e) {
            logger.error("❌ Packaging failed", e);
            return new PackageResult(false, "jar", 0);
        }
    }
    
    private Path packageMainJar(Path outputDir) {
        try {
            Path classesDir = Paths.get("target", "classes");
            if (!Files.exists(classesDir)) {
                logger.warn("⚠️  No classes directory found for JAR packaging");
                return null;
            }
            
            Path jarFile = outputDir.resolve("forge-main.jar");
            
            // Create JAR using jar command
            ProcessBuilder pb = new ProcessBuilder(
                "jar", "cf", jarFile.toString(), "-C", classesDir.toString(), "."
            );
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                logger.info("📦 Main JAR created successfully");
                return jarFile;
            } else {
                logger.error("❌ Failed to create JAR, exit code: " + exitCode);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("❌ Error creating main JAR", e);
            return null;
        }
    }
    
    private int packageAdditionalArtifacts(Path outputDir) {
        int count = 0;
        
        try {
            // Create sources JAR if source files exist
            Path sourcesDir = Paths.get("src", "main", "java");
            if (Files.exists(sourcesDir)) {
                Path sourcesJar = outputDir.resolve("forge-sources.jar");
                if (createSourcesJar(sourcesDir, sourcesJar)) {
                    count++;
                }
            }
            
            // Create javadoc JAR if documentation exists
            if (generateJavadoc(outputDir)) {
                count++;
            }
            
        } catch (Exception e) {
            logger.error("❌ Error creating additional artifacts", e);
        }
        
        return count;
    }
    
    private boolean createSourcesJar(Path sourcesDir, Path jarFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "jar", "cf", jarFile.toString(), "-C", sourcesDir.toString(), "."
            );
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            return exitCode == 0;
        } catch (Exception e) {
            logger.error("❌ Error creating sources JAR", e);
            return false;
        }
    }
    
    private boolean generateJavadoc(Path outputDir) {
        try {
            Path docDir = outputDir.resolve("javadoc");
            Files.createDirectories(docDir);
            
            // Generate javadoc for main sources
            Path sourcesDir = Paths.get("src", "main", "java");
            if (!Files.exists(sourcesDir)) {
                return false;
            }
            
            ProcessBuilder pb = new ProcessBuilder(
                "javadoc", "-d", docDir.toString(), 
                "-sourcepath", sourcesDir.toString(),
                "-subpackages", "io.github.yasmramos.forge"
            );
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                // Create JAR from javadoc
                Path javadocJar = outputDir.resolve("forge-javadoc.jar");
                ProcessBuilder jarPb = new ProcessBuilder(
                    "jar", "cf", javadocJar.toString(), "-C", docDir.toString(), "."
                );
                
                Process jarProcess = jarPb.start();
                int jarExitCode = jarProcess.waitFor();
                
                return jarExitCode == 0;
            }
            
            return false;
        } catch (Exception e) {
            logger.error("❌ Error generating javadoc", e);
            return false;
        }
    }
    
    private TestResult runTests(PackageResult packageResult) {
        logger.info("🧪 Running tests...");
        
        try {
            // Find test files
            List<Path> testFiles = findTestFiles();
            
            if (testFiles.isEmpty()) {
                logger.info("🧪 No test files found, skipping test execution");
                return new TestResult(true, 0, 0);
            }
            
            // Compile and run tests
            int totalTests = testFiles.size();
            int passedTests = 0;
            
            for (Path testFile : testFiles) {
                try {
                    if (runSingleTest(testFile)) {
                        passedTests++;
                    }
                } catch (Exception e) {
                    logger.error("❌ Test failed: " + testFile, e);
                }
            }
            
            logger.info("🧪 Test execution complete: " + passedTests + "/" + totalTests + " passed");
            return new TestResult(passedTests == totalTests, totalTests, passedTests);
            
        } catch (Exception e) {
            logger.error("❌ Test execution failed", e);
            return new TestResult(false, 0, 0);
        }
    }
    
    private List<Path> findTestFiles() {
        List<Path> testFiles = new ArrayList<>();
        
        try {
            // Common test directory patterns
            Path[] testDirs = {
                Paths.get("src", "test", "java"),
                Paths.get("test"),
                Paths.get("src", "test")
            };
            
            for (Path testDir : testDirs) {
                if (Files.exists(testDir)) {
                    Files.walk(testDir)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            String fileName = path.getFileName().toString().toLowerCase();
                            return fileName.contains("test") || fileName.contains("spec");
                        })
                        .forEach(testFiles::add);
                }
            }
            
        } catch (IOException e) {
            logger.error("❌ Error finding test files", e);
        }
        
        return testFiles;
    }
    
    private boolean runSingleTest(Path testFile) {
        try {
            // Simple test execution - compile and run if it has a main method
            String fileName = testFile.getFileName().toString();
            String className = fileName.substring(0, fileName.length() - 5); // Remove .java
            
            logger.debug("🧪 Running test: " + className);
            
            // Check if test has a main method
            String content = Files.readString(testFile);
            if (!content.contains("public static void main")) {
                logger.debug("🧪 Skipping test (no main method): " + className);
                return true; // Skip tests without main method
            }
            
            // Compile test
            ProcessBuilder compilePb = new ProcessBuilder(
                "javac", "-cp", "target/classes:target/forge-output/forge-main.jar", 
                testFile.toString()
            );
            
            Process compileProcess = compilePb.start();
            int compileExit = compileProcess.waitFor();
            
            if (compileExit != 0) {
                logger.error("❌ Test compilation failed: " + className);
                return false;
            }
            
            // Run test
            ProcessBuilder runPb = new ProcessBuilder(
                "java", "-cp", "target/classes:target/forge-output/forge-main.jar:.", 
                className
            );
            
            Process runProcess = runPb.start();
            int runExit = runProcess.waitFor();
            
            return runExit == 0;
            
        } catch (Exception e) {
            logger.error("❌ Error running test: " + testFile, e);
            return false;
        }
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