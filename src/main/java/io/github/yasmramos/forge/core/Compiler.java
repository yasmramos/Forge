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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    /** First {@code package a.b.c;} declaration in a source file, if any. */
    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private final Logger logger = LoggerFactory.getLogger(Compiler.class);
    /** In-process Java compiler (JSR-199); null when only an external javac exists. */
    private final JavaCompiler toolCompiler;
    /** Extra classpath entries (e.g. third-party libraries) injected externally. */
    private String classpath = "";
    /** Stable project identity (group:name) mixed into cache keys; see ForgeCache. */
    private String projectId = "";

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
    /**
     * Set the stable project identity (group:name) used to scope cache keys.
     *
     * @param projectId identity stable across machines; empty to disable scoping
     */
    public void setProjectId(String projectId) {
        this.projectId = projectId != null ? projectId : "";
    }

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
        // Absolute path: the JVM working directory may differ between runs
        // (e.g. Surefire sets CWD to target/ during tests), so a relative
        // output path would produce nested/wrong class locations.
        return compileBatch(files, dependencyResolution, cache, new File("target/classes").getAbsolutePath());
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

        // A source file reachable from more than one configured source root (or
        // the same root declared twice) must be compiled once: duplicates
        // inflate the file count and make javac report the same file twice.
        sourceFiles = new ArrayList<>(new LinkedHashSet<>(sourceFiles));

        // A file is a cache hit only if its content is cached AND its compiled
        // output is still on disk. Validity is content-based (AGENT.md section
        // 3); the presence check stops a warm cache from emitting an incomplete
        // build when outputs were lost (a clean, a partial remote-cache restore,
        // or a crash mid-write keeps ~/.forge/cache but loses class files). It
        // is per file, so a single lost class is regenerated even when the other
        // sources are unchanged.
        List<Path> staleFiles = new ArrayList<>();
        int cachedCount = 0;
        Map<String, Object> depMap = dependencyResolution != null && !dependencyResolution.getDependencies().isEmpty()
            ? createDependencyMap(dependencyResolution) : null;

        for (Path sourceFile : sourceFiles) {
            String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), depMap, projectId);
            boolean cached = cache != null && cache.isValid(cacheKey, sourceFile.toFile());
            if (cached && compiledOutputPresent(sourceFile, Paths.get(outputDir), cache.get(cacheKey))) {
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
            // Cache each successfully compiled file, recording every class it
            // produced so a later hit can prove the whole output is still there.
            Path outputPath = Paths.get(outputDir);
            for (Path sourceFile : staleFiles) {
                String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), depMap, projectId);
                ForgeCache.CacheEntry cacheEntry = new ForgeCache.CacheEntry(
                    sourceFile.toFile().lastModified(),
                    outcome.output.getBytes(StandardCharsets.UTF_8)
                );
                List<String> produced = producedClasses(sourceFile, outputPath);
                if (!produced.isEmpty()) {
                    cacheEntry.getMetadata().put(CLASSES_METADATA_KEY, String.join("\n", produced));
                }
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

    /** Metadata key holding the class files a cache entry stands for. */
    private static final String CLASSES_METADATA_KEY = "classes";

    /**
     * True when every class file a cache entry claims to have produced is
     * still on disk.
     *
     * <p>The top-level class alone is not enough: losing {@code Outer$Inner.class}
     * while {@code Outer.class} survives would otherwise be a silent cache hit
     * and an incomplete build - the same failure mode the per-file presence
     * check exists to prevent. When the entry carries the recorded class list
     * (every entry written since that list was introduced) all of them are
     * checked; legacy entries without it fall back to the top-level name.</p>
     */
    private static boolean compiledOutputPresent(Path sourceFile, Path outputDir,
                                                 ForgeCache.CacheEntry entry) {
        String recorded = entry != null ? entry.getMetadata().get(CLASSES_METADATA_KEY) : null;
        if (recorded == null || recorded.isEmpty()) {
            return compiledClassExists(sourceFile, outputDir);
        }
        for (String relative : recorded.split("\n")) {
            if (!relative.isEmpty() && !Files.isRegularFile(outputDir.resolve(relative))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Class files produced by compiling {@code sourceFile}: the top-level class
     * plus its nested and anonymous classes ({@code Name$*.class}), as paths
     * relative to {@code outputDir}. Empty when none are found (e.g. the file
     * declares only package-private types under another name).
     */
    private static List<String> producedClasses(Path sourceFile, Path outputDir) {
        String fileName = sourceFile.getFileName().toString();
        String baseName = fileName.endsWith(".java")
            ? fileName.substring(0, fileName.length() - ".java".length())
            : fileName;
        String packagePath = readPackagePath(sourceFile);
        Path packageDir = packagePath.isEmpty() ? outputDir : outputDir.resolve(packagePath);
        if (!Files.isDirectory(packageDir)) {
            return java.util.Collections.emptyList();
        }
        List<String> classes = new ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(packageDir)) {
            entries.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".class")
                        && (n.equals(baseName + ".class") || n.startsWith(baseName + "$"));
                })
                .sorted()
                .forEach(p -> classes.add(outputDir.relativize(p).toString().replace(File.separatorChar, '/')));
        } catch (java.io.IOException e) {
            // Unreadable output: record nothing and let the top-level check stand.
            return java.util.Collections.emptyList();
        }
        return classes;
    }

    /**
     * Fallback presence check for cache entries written before the produced
     * class list was recorded: the top-level class name matches the source file
     * name and lives in its package directory (JLS 7.6).
     */
    private static boolean compiledClassExists(Path sourceFile, Path outputDir) {
        String fileName = sourceFile.getFileName().toString();
        String baseName = fileName.endsWith(".java")
            ? fileName.substring(0, fileName.length() - ".java".length())
            : fileName;
        String packagePath = readPackagePath(sourceFile);
        Path classFile = packagePath.isEmpty()
            ? outputDir.resolve(baseName + ".class")
            : outputDir.resolve(packagePath).resolve(baseName + ".class");
        return Files.isRegularFile(classFile);
    }

    /** Directory form of the {@code package} declaration, or "" for the default package. */
    private static String readPackagePath(Path sourceFile) {
        try {
            for (String line : Files.readAllLines(sourceFile, StandardCharsets.UTF_8)) {
                Matcher m = PACKAGE_PATTERN.matcher(line);
                if (m.find()) {
                    return m.group(1).trim().replace('.', File.separatorChar);
                }
            }
        } catch (java.io.IOException | PatternSyntaxException e) {
            // Unreadable source: fall back to the default package; the compile
            // step will surface the real error.
        }
        return "";
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

            String classpath = buildClasspath(dependencyResolution, outputDir);
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
            String classpath = buildClasspath(dependencyResolution, outputDir);
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
    
    private String buildClasspath(DependencyResolution dependencyResolution, String outputDir) {
        StringBuilder classpathBuilder = new StringBuilder();

        // The module's own output directory comes first. Incremental builds
        // recompile only stale sources, and those routinely reference classes
        // whose sources are unchanged and therefore absent from this batch;
        // without outputDir on the classpath javac cannot resolve them.
        if (outputDir != null && !outputDir.isEmpty()) {
            classpathBuilder.append(outputDir);
        }

        // Then externally provided classpath entries (resolved by the engine)
        if (!classpath.isEmpty()) {
            if (classpathBuilder.length() > 0) {
                classpathBuilder.append(File.pathSeparator);
            }
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