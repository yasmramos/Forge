package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.cache.ForgeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * High-Performance Compiler for Forge Build System.
 *
 * <p>Compiles sources in a single batch using the in-process JSR-199 Java
 * compiler API ({@link javax.tools.JavaCompiler}) with incremental cache
 * support. Running javac in-process avoids spawning one JVM per build and
 * provides structured diagnostics instead of parsed console output.</p>
 *
 * <p>If the tool provider is unavailable (e.g. running on a JRE without the
 * JDK compiler module), the compiler falls back to launching the external
 * {@code javac} executable as before.</p>
 */
public class Compiler {

    private final Logger logger = LoggerFactory.getLogger(Compiler.class);
    /** In-process Java compiler (JSR-199); null when only an external javac exists. */
    private final JavaCompiler toolCompiler;
    /** Extra classpath entries (e.g. third-party libraries) injected externally. */
    private String classpath = "";

    /**
     * Create a new compiler.
     *
     * <p>No thread pool is allocated eagerly: batch compilation runs in the
     * calling thread, so an unused executor would only leak threads.</p>
     */
    public Compiler() {
        this.toolCompiler = ToolProvider.getSystemJavaCompiler();
        if (this.toolCompiler == null) {
            logger.warn("No in-process Java compiler available; falling back to external javac process");
        }
    }

    /**
     * Whether this compiler can run javac in-process via the JSR-199 API.
     *
     * @return true when a system Java compiler is available
     */
    public boolean isInProcessCompilationAvailable() {
        return toolCompiler != null;
    }

    /**
     * Set additional classpath entries used during compilation.
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
     * Compile all sources in a single batch.
     *
     * <p>Batch compilation is required because Java sources reference each other:
     * compiling one file at a time fails with unresolved symbols unless every
     * sibling source is passed together. The preferred path is the in-process
     * JSR-199 compiler API, which avoids spawning an external JVM per build;
     * only when no system compiler is available does it fall back to launching
     * the {@code javac} executable.</p>
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
        } catch (java.io.IOException e) {
            logger.error("Could not create output directory: " + outputDir, e);
            return new CompilationResult(false, sourceFiles.size(), cachedCount, cachedCount);
        }

        CompileOutcome outcome = toolCompiler != null
            ? executeInProcess(staleFiles, dependencyResolution, outputDir)
            : executeJavac(staleFiles, dependencyResolution, outputDir);

        if (outcome.success) {
            // Cache each successfully compiled file
            for (Path sourceFile : staleFiles) {
                String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), depMap);
                ForgeCache.CacheEntry cacheEntry = new ForgeCache.CacheEntry(
                    sourceFile.toFile().lastModified(),
                    outcome.output.getBytes(StandardCharsets.UTF_8)
                );
                if (cache != null) {
                    cache.put(cacheKey, cacheEntry);
                }
            }
            int compiled = staleFiles.size() + cachedCount;
            return new CompilationResult(true, sourceFiles.size(), compiled, cachedCount);
        } else {
            logger.error("Batch compilation failed for " + staleFiles.size() + " file(s)");
            logger.error("compiler output:\n" + outcome.error);
            return new CompilationResult(false, sourceFiles.size(), cachedCount, cachedCount);
        }
    }

    public CompilationResult compileIncremental(List<Path> changedSources, ForgeCache cache) {
        logger.info("Incremental compilation of " + changedSources.size() + " changed files");
        // Delegate to the batch compiler so cross-file symbol references resolve
        return compileBatch(changedSources, null, cache, "target/classes");
    }

    /**
     * Run the JSR-199 in-process compiler over the given sources.
     *
     * @return structured outcome with success flag and human-readable messages
     */
    private CompileOutcome executeInProcess(List<Path> sourceFiles, DependencyResolution dependencyResolution, String outputDir) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                 toolCompiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Arrays.asList(new File(outputDir)));

            String classpath = buildClasspath(dependencyResolution);
            if (!classpath.isEmpty()) {
                List<File> cpFiles = new ArrayList<>();
                for (String entry : classpath.split(File.pathSeparator)) {
                    if (!entry.isEmpty()) {
                        cpFiles.add(new File(entry));
                    }
                }
                fileManager.setLocation(StandardLocation.CLASS_PATH, cpFiles);
            }

            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(toFileList(sourceFiles));

            List<String> options = Arrays.asList("-encoding", "UTF-8", "-proc:none");

            JavaCompiler.CompilationTask task = toolCompiler.getTask(
                    null, fileManager, diagnostics, options, null, compilationUnits);
            boolean success = task.call();

            StringBuilder errors = new StringBuilder();
            StringBuilder notes = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                String message = diagnostic.toString() + System.lineSeparator();
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.append(message);
                } else {
                    notes.append(message);
                }
            }
            return new CompileOutcome(success, notes.toString(), errors.toString());

        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new CompileOutcome(false, "", message);
        }
    }

    /**
     * Fallback path: launch the external {@code javac} executable in one process.
     *
     * <p>Only used when no in-process system compiler is available.</p>
     */
    private CompileOutcome executeJavac(List<Path> sourceFiles, DependencyResolution dependencyResolution, String outputDir) {
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

            return new CompileOutcome(exitCode == 0,
                    new String(outputBytes, StandardCharsets.UTF_8),
                    new String(errorBytes, StandardCharsets.UTF_8));

        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new CompileOutcome(false, "", message);
        }
    }

    private static List<File> toFileList(List<Path> paths) {
        List<File> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            files.add(path.toFile());
        }
        return files;
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
    
    /**
     * Immutable outcome of a compilation run, shared by the in-process and
     * external-javac execution paths.
     */
    private static class CompileOutcome {
        private final boolean success;
        private final String output;
        private final String error;

        CompileOutcome(boolean success, String output, String error) {
            this.success = success;
            this.output = output != null ? output : "";
            this.error = error != null ? error : "";
        }
    }
}