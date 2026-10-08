package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.cache.ForgeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * High-Performance Compiler for Forge Build System.
 * Compiles sources in a single batch javac invocation with incremental
 * cache support.
 */
public class Compiler {
    
    private final Logger logger = LoggerFactory.getLogger(Compiler.class);
    /** Extra classpath entries (e.g. third-party libraries) injected externally. */
    private String classpath = "";

    /**
     * Create a new compiler.
     *
     * <p>No thread pool is allocated eagerly: batch compilation runs in the
     * calling thread, so an unused executor would only leak threads.</p>
     */
    public Compiler() {
    }

    /**
     * Set additional classpath entries used when invoking javac.
     *
     * @param classpath platform-separated list of jars/directories
     */
    public void setClasspath(String classpath) {
        this.classpath = classpath != null ? classpath : "";
    }

    public String getClasspath() {
        return classpath;
    }
    
    /**
     * Compile a single source file against the given dependency resolution.
     */
    public CompilationResult compile(Path sourceFile, DependencyResolution dependencyResolution, ForgeCache cache) {
        List<Path> files = new ArrayList<>();
        files.add(sourceFile);
        return compileBatch(files, dependencyResolution, cache, "target/classes");
    }

    /**
     * Compile all sources in a single javac invocation.
     *
     * <p>Batch compilation is required because Java sources reference each other:
     * compiling one file at a time fails with unresolved symbols unless every
     * sibling source is passed on the command line. A single invocation also
     * avoids spawning one JVM per file.</p>
     *
     * @param sourceFiles          all source files to compile together
     * @param dependencyResolution resolved external dependencies (may be null)
     * @param cache                build cache; entries are keyed per source file
     * @param outputDir            directory where class files are emitted
     * @return aggregated compilation result
     */
    public CompilationResult compileBatch(List<Path> sourceFiles, DependencyResolution dependencyResolution,
                                          ForgeCache cache, String outputDir) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new CompilationResult(true, 0, 0);
        }

        // Filter out files that are already cached as valid
        List<Path> staleFiles = new ArrayList<>();
        int cachedCount = 0;
        Map<String, Object> depMap = dependencyResolution != null && !dependencyResolution.getDependencies().isEmpty()
            ? createDependencyMap(dependencyResolution) : null;

        for (Path sourceFile : sourceFiles) {
            String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), depMap);
            if (cache != null && cache.isValid(cacheKey, sourceFile.toFile())) {
                logger.debug("Using cached compilation for: " + sourceFile);
                cachedCount++;
            } else {
                staleFiles.add(sourceFile);
            }
        }

        if (staleFiles.isEmpty()) {
            logger.info("All " + cachedCount + " files up to date (cache hit)");
            return new CompilationResult(true, sourceFiles.size(), sourceFiles.size(), cachedCount);
        }

        logger.info("Compiling " + staleFiles.size() + " file(s) in one batch (" + cachedCount + " cached)");

        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (IOException e) {
            logger.error("Could not create output directory: " + outputDir, e);
            return new CompilationResult(false, sourceFiles.size(), cachedCount, cachedCount);
        }

        ProcessResult result = executeJavac(staleFiles, dependencyResolution, outputDir);

        if (result.isSuccess()) {
            // Cache each successfully compiled file
            for (Path sourceFile : staleFiles) {
                String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), depMap);
                ForgeCache.CacheEntry cacheEntry = new ForgeCache.CacheEntry(
                    sourceFile.toFile().lastModified(),
                    result.getOutput()
                );
                if (cache != null) {
                    cache.put(cacheKey, cacheEntry);
                }
            }
            int compiled = staleFiles.size() + cachedCount;
            return new CompilationResult(true, sourceFiles.size(), compiled, cachedCount);
        } else {
            logger.error("Batch compilation failed for " + staleFiles.size() + " file(s)");
            logger.error("javac output:\n" + result.getErrorString());
            return new CompilationResult(false, sourceFiles.size(), cachedCount, cachedCount);
        }
    }

    public CompilationResult compileIncremental(List<Path> changedSources, ForgeCache cache) {
        logger.info("Incremental compilation of " + changedSources.size() + " changed files");
        // Delegate to the batch compiler so cross-file symbol references resolve
        return compileBatch(changedSources, null, cache, "target/classes");
    }

    private ProcessResult executeJavac(List<Path> sourceFiles, DependencyResolution dependencyResolution, String outputDir) {
        try {
            List<String> command = new ArrayList<>();
            command.add("javac");
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-proc:none"); // javac reads sources from disk; no need for sourcepath lookup
            command.add("-d");
            command.add(outputDir);

            // Add classpath
            String classpath = buildClasspath(dependencyResolution);
            if (!classpath.isEmpty()) {
                command.add("-cp");
                command.add(classpath);
            }

            // Pass every source file in one invocation so interdependent classes resolve
            for (Path sourceFile : sourceFiles) {
                command.add(sourceFile.toString());
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();

            // Read output and error streams fully before waiting to avoid pipe-buffer deadlock
            byte[] outputBytes = process.getInputStream().readAllBytes();
            byte[] errorBytes = process.getErrorStream().readAllBytes();

            int exitCode = process.waitFor();

            return new ProcessResult(exitCode == 0, outputBytes, errorBytes);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ProcessResult(false, new byte[0], message.getBytes());
        }
    }
    
    private String buildClasspath(DependencyResolution dependencyResolution) {
        StringBuilder classpathBuilder = new StringBuilder();

        // Start with externally provided classpath entries (resolved by the engine)
        if (!classpath.isEmpty()) {
            classpathBuilder.append(classpath);
        }

        if (dependencyResolution != null) {
            for (io.github.yasmramos.forge.model.DependencyInfo dep : dependencyResolution.getDependencies()) {
                if (dep.isResolved() && dep.getLocalFile().exists()) {
                    if (classpathBuilder.length() > 0) {
                        classpathBuilder.append(File.pathSeparator);
                    }
                    classpathBuilder.append(dep.getLocalFile().getAbsolutePath());
                }
            }
        }

        return classpathBuilder.toString();
    }
    
    private Map<String, Object> createDependencyMap(DependencyResolution dependencyResolution) {
        // Create a simple map representation for cache key generation
        java.util.Map<String, Object> depMap = new java.util.HashMap<>();
        for (io.github.yasmramos.forge.model.DependencyInfo dep : dependencyResolution.getDependencies()) {
            depMap.put(dep.getName(), dep.getVersion());
        }
        return depMap;
    }
    
    /**
     * Release any resources held by this compiler.
     *
     * <p>Present for API compatibility with callers that shut the compiler
     * down after a build; there is currently nothing to release.</p>
     */
    public void shutdown() {
        // No-op: batch compilation runs in the calling thread.
    }
    
    private static class ProcessResult {
        private final boolean success;
        private final byte[] output;
        private final byte[] error;
        
        public ProcessResult(boolean success, byte[] output, byte[] error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }
        
        public boolean isSuccess() { return success; }
        public byte[] getOutput() { return output; }
        public byte[] getError() { return error; }

        /** Human-readable form of the captured stderr output. */
        public String getErrorString() {
            return new String(error, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Human-readable form of the captured stdout output. */
        public String getOutputString() {
            return new String(output, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}