package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.core.processor.AnnotationProcessors;
import io.github.yasmramos.forge.model.DependencyInfo;
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
     * Explicit annotation-processor class names from {@code [compile].processors}
     * (AGENT.md section 23). Empty means "discover them from the compile
     * classpath", which is the default.
     */
    private List<String> processorNames = new ArrayList<>();

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

    /**
     * Declare annotation processors explicitly ({@code [compile].processors},
     * section 23). When empty the compiler discovers them from the compile
     * classpath via {@code META-INF/services}.
     *
     * @param processorNames fully-qualified processor class names; null to clear
     */
    public void setProcessorNames(List<String> processorNames) {
        this.processorNames = processorNames != null ? new ArrayList<>(processorNames)
            : new ArrayList<>();
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
        return compileBatch(sourceFiles, dependencyResolution, cache, outputDir, null);
    }

    /**
     * Compile all sources in a single batch, cascading cache invalidation.
     *
     * @param sourceFiles          all source files to compile together
     * @param dependencyResolution resolved external dependencies (may be null)
     * @param cache                build cache; entries are keyed per source file
     * @param outputDir            directory where class files are emitted
     * @param sourceHashes         per-file effective hashes folding in transitive
     *                             source dependencies; computed here when null
     * @return aggregated compilation result
     */
    public CompilationResult compileBatch(List<Path> sourceFiles, DependencyResolution dependencyResolution,
                                          ForgeCache cache, String outputDir, Map<Path, String> sourceHashes) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new CompilationResult(true, 0, 0);
        }

        // A source file reachable from more than one configured source root (or
        // the same root declared twice) must be compiled once: duplicates
        // inflate the file count and make javac report the same file twice.
        sourceFiles = new ArrayList<>(new LinkedHashSet<>(sourceFiles));

        // Effective hash per file: own content plus its transitive source
        // dependencies. Keying on this instead of the raw content is what makes
        // a change in A.java invalidate the cached B.class that inlined A's
        // constants or linked against A's API (AGENT.md sections 3 and 17).
        Map<Path, String> effectiveHashes = sourceHashes != null
            ? sourceHashes : SourceDependencies.effectiveHashes(sourceFiles);

        // A file is a hit only if an entry exists and its stored outputs can be
        // put back in place. Validity is content-based (AGENT.md section 3);
        // restoring the outputs (rather than trusting whatever class files sit
        // on disk) keeps a clean, a partial remote-cache restore, or a crash
        // mid-write from producing an incomplete build.
        List<Path> staleFiles = new ArrayList<>();
        int cachedCount = 0;
        Map<String, Object> depMap = dependencyResolution != null && !dependencyResolution.getDependencies().isEmpty()
            ? createDependencyMap(dependencyResolution) : null;

        for (Path sourceFile : sourceFiles) {
            String cacheKey = ForgeCache.generateKey(
                sourceFile.toFile(), depMap, projectId, effectiveHashes.get(sourceFile));
            boolean cached = cache != null && cache.isValid(cacheKey, sourceFile.toFile());
            if (cached && restoreOutputs(cache.get(cacheKey), Paths.get(outputDir))) {
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
                String cacheKey = ForgeCache.generateKey(
                    sourceFile.toFile(), depMap, projectId, effectiveHashes.get(sourceFile));
                ForgeCache.CacheEntry cacheEntry = new ForgeCache.CacheEntry(
                    sourceFile.toFile().lastModified(),
                    outcome.output.getBytes(StandardCharsets.UTF_8)
                );
                List<String> produced = producedClasses(sourceFile, outputPath);
                if (!produced.isEmpty()) {
                    for (String relative : produced) {
                        try {
                            cacheEntry.putOutput(relative, Files.readAllBytes(outputPath.resolve(relative)));
                        } catch (java.io.IOException e) {
                            // Output vanished between compile and caching: drop it
                            // from the list so the entry is not trusted later.
                            logger.debug("Could not read produced class " + relative, e);
                        }
                    }
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

    /**
     * Put a cache entry's stored class files back in position, returning whether
     * the entry could be trusted as a hit.
     *
     * <p>Outputs are stored and restored rather than merely checked for presence
     * (AGENT.md section 3): the same key can be reached again after an edit that
     * changed the compiled bytes, so a hit must write the cached class files
     * back, not assume whatever is on disk belongs to this key.</p>
     */
    private boolean restoreOutputs(ForgeCache.CacheEntry entry, Path outputDir) {
        if (entry == null || entry.getOutputs().isEmpty()) {
            // No stored outputs (an entry from before they were kept, or a file
            // that produced none): treat as a miss so correctness never depends
            // on whatever class file happens to sit on disk. The recompile
            // rewrites the entry with its outputs.
            return false;
        }
        try {
            for (Map.Entry<String, byte[]> output : entry.getOutputs().entrySet()) {
                Path target = outputDir.resolve(output.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, output.getValue());
            }
            return true;
        } catch (java.io.IOException e) {
            logger.debug("Failed to restore cached outputs", e);
            return false;
        }
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

            List<String> options = buildProcessorOptions(dependencyResolution, classpath, fileManager);

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
            command.add("-d");
            command.add(outputDir);

            // Add classpath
            String classpath = buildClasspath(dependencyResolution, outputDir);
            if (!classpath.isEmpty()) {
                command.add("-cp");
                command.add(classpath);
            }

            // Annotation processing (section 23): a dedicated -processorpath
            // keeps processors off the compile classpath; -proc:none is used
            // only when the classpath declares none, so unrelated libraries
            // do not trigger accidental processor discovery.
            command.addAll(buildProcessorCommandOptions(dependencyResolution, classpath));

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
            for (DependencyInfo dep : dependencyResolution.getDependencies()) {
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
        for (DependencyInfo dep : dependencyResolution.getDependencies()) {
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
     * javac options for the in-process compiler: always {@code -encoding}; then
     * either annotation processing wired to the classpath-declared processors
     * (section 23) or {@code -proc:none} when there are none.
     *
     * <p>Discovery scans the resolved dependency artifacts, not the whole
     * classpath string: the latter also carries Forge's own runtime libraries
     * (injected so javac can resolve third-party symbols), and a processor
     * hiding there must never run against user code.</p>
     */
    private List<String> buildProcessorOptions(DependencyResolution dependencyResolution,
                                               String classpath,
                                               StandardJavaFileManager fileManager) {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");

        if (!processorNames.isEmpty()) {
            options.add("-processor");
            options.add(String.join(",", processorNames));
            setProcessorPath(fileManager, classpath);
            return options;
        }

        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(
                resolvedFiles(dependencyResolution));
        if (discovery.isEmpty()) {
            options.add("-proc:none");
            return options;
        }
        options.add("-processor");
        options.add(String.join(",", discovery.getNames()));
        setProcessorPath(fileManager, join(discovery.getProcessorPath()));
        return options;
    }

    /** Same decision as the in-process path, for the external javac executable. */
    private List<String> buildProcessorCommandOptions(DependencyResolution dependencyResolution,
                                                      String classpath) {
        List<String> options = new ArrayList<>();
        if (!processorNames.isEmpty()) {
            options.add("-processor");
            options.add(String.join(",", processorNames));
            options.add("-processorpath");
            options.add(classpath);
            return options;
        }
        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(
                resolvedFiles(dependencyResolution));
        if (discovery.isEmpty()) {
            options.add("-proc:none");
            return options;
        }
        options.add("-processor");
        options.add(String.join(",", discovery.getNames()));
        options.add("-processorpath");
        options.add(join(discovery.getProcessorPath()));
        return options;
    }

    /** Resolved dependency files, for processor discovery. */
    private static List<File> resolvedFiles(DependencyResolution dependencyResolution) {
        List<File> files = new ArrayList<>();
        if (dependencyResolution == null) {
            return files;
        }
        for (DependencyInfo dep : dependencyResolution.getDependencies()) {
            if (dep.isResolved() && dep.getLocalFile().exists()) {
                files.add(dep.getLocalFile());
            }
        }
        return files;
    }

    private static void setProcessorPath(StandardJavaFileManager fileManager, String processorPath) {
        List<File> entries = splitClasspath(processorPath);
        if (entries.isEmpty()) {
            return;
        }
        try {
            fileManager.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, entries);
        } catch (java.io.IOException e) {
            // Best effort: without the explicit location javac falls back to
            // the classpath, which still runs the discovered processors.
        }
    }

    private static String join(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File file : files) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(file.getAbsolutePath());
        }
        return sb.toString();
    }

    private static List<File> splitClasspath(String classpath) {
        List<File> entries = new ArrayList<>();
        if (classpath == null || classpath.isEmpty()) {
            return entries;
        }
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(new File(entry));
            }
        }
        return entries;
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