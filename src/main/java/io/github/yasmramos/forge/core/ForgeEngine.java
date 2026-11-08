package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.model.PackageResult;
import io.github.yasmramos.forge.cache.ForgeCache;
import io.github.yasmramos.forge.core.cache.TaskCache;
import io.github.yasmramos.forge.core.graph.TaskGraph;
import io.github.yasmramos.forge.core.graph.TaskScheduler;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.StandardTaskFactory;
import io.github.yasmramos.forge.core.task.TaskContext;
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
    private final Compiler compiler;
    private final BuildAnalyzer analyzer;
    private final File projectRoot;

    /**
     * Expose the loaded project configuration so CLI commands such as
     * {@code forge run} can read [exec] settings without re-parsing.
     */
    public ProjectConfig getConfig() {
        return config;
    }

    /**
     * Directory holding {@code forge.toml} and {@code forge.lock}. Exposed so CLI
     * commands (e.g. the multi-module builder) resolve siblings without assuming
     * the process working directory.
     */
    public File getProjectRoot() {
        return projectRoot;
    }
    
    public ForgeEngine(ProjectConfig config) {
        this(config, new File(System.getProperty("user.dir")));
    }

    /**
     * @param projectRoot directory holding {@code forge.toml} and {@code
     *                    forge.lock}; defaults to the process working directory
     *                    and is overridable so tests can run against a temp
     *                    project without mutating the real cwd.
     */
    public ForgeEngine(ProjectConfig config, File projectRoot) {
        this.config = config;
        this.projectRoot = projectRoot;

        this.cache = new ForgeCache();
        this.executor = Executors.newFixedThreadPool(
            Math.max(1, config.getBuildSettings().getThreads())
        );
        this.compiler = new Compiler();
        // Scope cache keys to this project's stable coordinates so the shared
        // ~/.forge/cache never serves another project's entry (AGENT.md 3, 17).
        this.compiler.setProjectId(config.getGroup() + ":" + config.getName());
        // Seed the compiler with third-party libraries available on the JVM classpath
        // (SLF4J, NightConfig, ...) so javac can resolve external symbols when building sources.
        this.compiler.setClasspath(System.getProperty("java.class.path"));
        // Explicit annotation processors, when declared (section 23); otherwise
        // the compiler discovers them from the resolved compile classpath.
        this.compiler.setProcessorNames(config.getProcessors());
        this.analyzer = new BuildAnalyzer();
    }
    
    /**
     * Execute full build process
     */
    public BuildResult build() {
        logger.info("Starting Forge build for project: " + config.getName());
        
        try {
            // Lock enforcement (AGENT.md section 7bis): a normal build never
            // re-resolves dependencies. If forge.lock exists it must match the
            // current forge.toml (config hash) and be internally intact;
            // otherwise the build fails with an actionable error before
            // touching any output. A missing lock is allowed for now
            // (bootstrap); the graph path already enforces it identically.
            io.github.yasmramos.forge.core.resolver.LockfileGenerator.enforceExistingLock(
                projectRoot);

            // Phase 1: Analyze project structure
            logger.info("Analyzing project structure...");
            ProjectAnalysis analysis = analyzer.analyzeProject(config);
            
            // Phase 2: Resolve dependencies through Maven Resolver (Aether).
            // AGENT.md section 2: never reimplement resolution; the same engine
            // that produced forge.lock resolves the compile classpath here.
            DependencyResolution dependencyResult = resolveWithAether();
            logger.info("Resolved " + dependencyResult.getSuccessCount()
                + " artifact(s) via Maven Resolver");
            
            // Phase 3: Compile sources
            logger.info("Compiling sources...");
            CompilationResult compilationResult = compileSources(analysis, dependencyResult);

            if (!compilationResult.isSuccess()) {
                logger.error("Compilation failed: " + compilationResult.getCompiledFiles()
                    + "/" + compilationResult.getTotalFiles() + " files compiled successfully");
                return new BuildResult(false, null, null);
            }

            // Phase 3b: Copy resources into the classes directory so the jar
            // contains them (AGENT.md section 22).
            copyResources();

            // Phase 4: Package artifacts
            logger.info("Packaging artifacts...");
            PackageResult packageResult = packageArtifacts(compilationResult);

            // Phase 5: Run tests after packaging, regardless of parallel/sequential mode
            logger.info("Running tests...");
            TestResult testResult = executeTests(packageResult);

            boolean buildSuccess = packageResult.isSuccess() && testResult.isSuccess();
            return new BuildResult(buildSuccess, packageResult, testResult);

        } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
            // Structured errors already answer what/why/how (AGENT.md 10bis):
            // carry them out so the CLI renders with --format=json/--debug
            // rather than flattening to a bare stack trace here.
            return new BuildResult(false, null, null, fe);
        } catch (Exception e) {
            logger.error("Build failed", e);
            return new BuildResult(false, null, null);
        } finally {
            shutdown();
        }
    }
    
    /**
     * Test-only pipeline (AGENT.md section 10, {@code forge test}): compile the
     * main sources, then compile and run the tests, without packaging. Shares the
     * exact compile and {@link io.github.yasmramos.forge.core.test.TestExecutor}
     * paths a full build uses, so {@code forge test} can never disagree with
     * {@code forge build} about whether the tests pass.
     */
    public BuildResult runTests() {
        logger.info("Running tests for project: " + config.getName());
        try {
            // Lock enforcement (AGENT.md section 7bis), identical to a build.
            io.github.yasmramos.forge.core.resolver.LockfileGenerator.enforceExistingLock(
                projectRoot);

            ProjectAnalysis analysis = analyzer.analyzeProject(config);
            DependencyResolution dependencyResult = resolveWithAether();
            CompilationResult compilationResult = compileSources(analysis, dependencyResult);

            if (!compilationResult.isSuccess()) {
                logger.error("Compilation failed: " + compilationResult.getCompiledFiles()
                    + "/" + compilationResult.getTotalFiles() + " files compiled successfully");
                return new BuildResult(false, null, null);
            }

            // Resources land in the classes dir before the tests run, so a test
            // reading a bundled resource sees the same file the jar will carry.
            copyResources();

            TestResult testResult = executeTests(null);
            return new BuildResult(testResult.isSuccess(), null, testResult);
        } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
            return new BuildResult(false, null, null, fe);
        } catch (Exception e) {
            logger.error("Test run failed", e);
            return new BuildResult(false, null, null);
        } finally {
            shutdown();
        }
    }

    /**
     * Incremental build (AGENT.md section 3): same compile path as a full build,
     * but staleness is decided by content. The whole source set goes to the batch
     * compiler, whose per-file content cache skips unchanged files; a failing
     * compilation fails the build and the process exit code.
     */
    public BuildResult buildIncremental() {
        logger.info("Starting incremental build...");

        try {
            // Lock enforcement (AGENT.md section 7bis), identical to a full build.
            io.github.yasmramos.forge.core.resolver.LockfileGenerator.enforceExistingLock(
                projectRoot);

            ProjectAnalysis analysis = analyzer.analyzeChanges(config);

            DependencyResolution dependencyResult = resolveWithAether();
            CompilationResult compilationResult = compiler.compileBatch(
                analysis.getSourceFiles(), dependencyResult, cache,
                resolveClassesDir().toString());

            if (!compilationResult.isSuccess()) {
                logger.error("Incremental compilation failed: "
                    + compilationResult.getCompiledFiles() + "/"
                    + compilationResult.getTotalFiles() + " files compiled successfully");
                return new BuildResult(false, null, null);
            }

            copyResources();

            PackageResult packageResult = new PackageResult(
                true, "incremental", compilationResult.getCompiledFiles());
            return new BuildResult(true, packageResult, null);

        } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
            return new BuildResult(false, null, null, fe);
        } catch (Exception e) {
            logger.error("Incremental build failed", e);
            return new BuildResult(false, null, null);
        } finally {
            shutdown();
        }
    }
    
    /**
     * Experimental task-graph build (AGENT.md decisions 1 and 3): the whole
     * graph is built and validated up front ("configure time"), so any bad
     * dependency reference or cycle fails before touching outputs. Then only
     * cache-missed tasks execute, in parallel over independent branches
     * ("execution time"). This path is opt-in until it replaces the legacy
     * phased pipeline as the default.
     */
    public TaskScheduler.Report buildWithTaskGraph() throws Exception {
        logger.info("Starting task-graph build for project: " + config.getName());

        File projectRoot = this.projectRoot;

        // Lock enforcement (AGENT.md section 7bis): a normal build never
        // re-resolves dependencies. If forge.lock exists it must match the
        // current forge.toml (config hash) and be internally intact; otherwise
        // the build fails with an actionable error before touching outputs.
        io.github.yasmramos.forge.core.resolver.LockfileGenerator.enforceExistingLock(projectRoot);

        TaskCache taskCache = new TaskCache(projectRoot, remoteCache());
        TaskContext context = new TaskContext(projectRoot);

        StandardTaskFactory factory =
            new StandardTaskFactory(config, projectRoot, resolveClassesDir());

        // Configure time: load declared plugins before building the graph
        // (AGENT.md sections 4 and 16bis). Loading happens in the same core
        // codebase that executes the build, so there is no split brain. Their
        // loaded identity - not just the declared coordinate - is folded into
        // the task cache key (section 3).
        io.github.yasmramos.forge.core.plugin.LoadedPlugins plugins =
            io.github.yasmramos.forge.core.plugin.PluginBootstrap.forProject(config).load();
        io.github.yasmramos.forge.core.task.BuildEnvironment environment =
            io.github.yasmramos.forge.core.task.BuildEnvironment.forPlugins(plugins.idVersions());
        if (!plugins.isEmpty()) {
            logger.info("Loaded " + plugins.catalog().size() + " plugin(s): "
                + plugins.idVersions());
            factory.setEnvironment(environment);
        }

        TaskGraph graph = new TaskGraph();
        graph.add(factory.compile());
        graph.add(factory.test());

        // Packaging (AGENT.md section 4: "register packaging types"). A project
        // selecting a plugin-provided type produces its artifact through the
        // plugin instead of the built-in jar; selecting a type no loaded plugin
        // provides fails here, at configure time, never by silently producing a
        // jar the project did not ask for.
        io.github.yasmramos.forge.core.plugin.PluginPackagingContributor packaging =
            new io.github.yasmramos.forge.core.plugin.PluginPackagingContributor(
                plugins, config, projectRoot, resolveClassesDir(), environment);
        if ("jar".equals(packaging.selectedType())) {
            graph.add(factory.packageTask());
        } else {
            for (ForgeTask t : packaging.tasks()) {
                graph.add(t);
                logger.info("Plugin packaging task registered: " + t.getName()
                    + " (" + t.getType() + ")");
            }
        }

        // Plugin-contributed tasks (AGENT.md sections 1 and 4): every task type
        // a plugin registered with a factory contributes its instances here, at
        // configure time, before the graph is validated. A bad contribution
        // (duplicate name, built-in shadowing) fails now, never mid-build.
        if (!plugins.isEmpty()) {
            io.github.yasmramos.forge.core.plugin.PluginTaskContributor contributor =
                new io.github.yasmramos.forge.core.plugin.PluginTaskContributor(
                    plugins, config, projectRoot, environment);
            for (ForgeTask t : contributor.tasks()) {
                graph.add(t);
                logger.info("Plugin task registered: " + t.getName()
                    + " (" + t.getType() + ")");
            }

            // Plugin compilers (AGENT.md section 4: "register compilers for
            // languages"). Each registered language contributes a compile task
            // that discovers its own sources and feeds the same classes
            // directory, so Kotlin/Scala land where the Java compile does.
            io.github.yasmramos.forge.core.plugin.PluginCompilationContributor compilers =
                new io.github.yasmramos.forge.core.plugin.PluginCompilationContributor(
                    plugins, config, projectRoot, resolveClassesDir(), environment);
            for (ForgeTask t : compilers.tasks()) {
                graph.add(t);
                logger.info("Plugin compiler task registered: " + t.getName()
                    + " (" + t.getType() + ")");
            }

            // Plugin test runners (AGENT.md section 4: "register test runners").
            // Each runner contributes a test task that discovers the tests it
            // claims by extension and depends on the built-in test task.
            io.github.yasmramos.forge.core.plugin.PluginTestContributor testRunners =
                new io.github.yasmramos.forge.core.plugin.PluginTestContributor(
                    plugins, config, projectRoot, resolveClassesDir(), environment);
            for (ForgeTask t : testRunners.tasks()) {
                graph.add(t);
                logger.info("Plugin test runner task registered: " + t.getName()
                    + " (" + t.getType() + ")");
            }
        }

        // Configure time: validate dangling references and cycles first.
        graph.validate();

        int threads = config.getBuildSettings().getThreads();
        TaskScheduler scheduler = new TaskScheduler(threads);
        TaskScheduler.Report report = scheduler.execute(graph, context, taskCache);

        for (ForgeTask t : graph.topoOrder()) {
            logger.info("[" + t.getName() + "] " + report.outcomes.get(t.getName()));
        }
        // Reproducibility verification (AGENT.md section 17): for plugins that
        // declare reproducible = true, double-run their tasks and compare
        // outputs. Default is to warn; strict mode fails; "off" skips the check.
        if (!report.failed() && !config.getBuildSettings().isReproducibleOff()) {
            verifyPluginReproducibility(graph, report, plugins);
        }

        // End-of-build upload (AGENT.md section 16bis): only after the whole
        // build succeeded, so a failed build uploads nothing.
        if (!report.failed()) {
            taskCache.flushRemote();
        }
        logger.info("Task-graph build finished: " + report.cacheHits() + " cache hits, "
            + report.executed() + " executed");
        return report;
    }

    /**
     * Double-runs the plugin tasks that were actually executed this build and
     * compares their outputs (AGENT.md section 17). The verifier applies the
     * policy: warn by default, fail in strict mode. Cache hits are skipped:
     * their outputs came from the cache, not from the plugin, so re-running them
     * would not test the plugin's determinism.
     */
    private void verifyPluginReproducibility(TaskGraph graph, TaskScheduler.Report report,
                                             io.github.yasmramos.forge.core.plugin.LoadedPlugins plugins) {
        new io.github.yasmramos.forge.core.packageinfo.ReproducibilityVerifier(
            plugins, projectRoot, config.getBuildSettings().isReproducibleStrict())
            .verify(executedTasks(graph, report));
    }

    /**
     * The tasks that actually executed this build, in topological order. Cache
     * hits are excluded: their outputs came from the cache, not from the plugin,
     * so re-running them would not test the plugin's determinism (section 17).
     */
    static java.util.List<ForgeTask> executedTasks(TaskGraph graph, TaskScheduler.Report report) {
        java.util.List<ForgeTask> executed = new java.util.ArrayList<>();
        for (ForgeTask t : graph.topoOrder()) {
            if (report.outcomes.get(t.getName()) == TaskScheduler.Outcome.EXECUTED) {
                executed.add(t);
            }
        }
        return executed;
    }

    /**
     * The remote cache client for this project, or {@code null} for a local-only
     * cache (AGENT.md section 3). Configured through {@code [build] remote_cache}
     * in forge.toml or the {@code FORGE_REMOTE_CACHE} environment variable.
     */
    private io.github.yasmramos.forge.core.cache.RemoteCache remoteCache() {
        String url = config.getBuildSettings().getRemoteCacheUrl();
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        return new io.github.yasmramos.forge.core.cache.RemoteCache(url);
    }

    /**
     * Clean build artifacts
     */
    public void clean() {
        logger.info("Cleaning build artifacts...");
        
        try {
            {
                // Only remove build outputs: the compiled-classes directory
                // and the packaged-artifacts directory. Never wipe the whole
                // build base, which also holds tool state (e.g. Surefire's
                // working directory during tests).
                for (Path outputPath : List.of(
                        resolveClassesDir(), resolveBuildBaseDir().resolve("forge-output"))) {
                    if (!Files.exists(outputPath)) {
                        continue;
                    }
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
            logger.info("Clean completed");
            
        } catch (Exception e) {
            logger.error("Clean failed", e);
        }
    }
    
    /**
     * Copies declared resources into the compiled-classes directory
     * (AGENT.md section 22). Filtering is opt-in; an unchanged resource is left
     * untouched (content, never timestamps: section 3).
     */
    private void copyResources() {
        List<File> roots = resourceRoots();
        if (roots.isEmpty()) {
            return;
        }
        try {
            Map<String, String> filterProps = config.getBuildSettings().isResourceFiltering()
                ? filterProperties()
                : null;
            io.github.yasmramos.forge.core.resource.ResourceProcessor.Result result =
                io.github.yasmramos.forge.core.resource.ResourceProcessor.copy(
                    roots, resolveClassesDir(), filterProps != null, filterProps);
            logger.info("Resources: " + result.getCopied() + " copied, "
                + result.getUnchanged() + " unchanged (" + result.getTotal() + " total)");
        } catch (IOException e) {
            logger.warn("Resource copy failed: " + e.getMessage());
        }
    }

    /** Resource directories declared in forge.toml, defaulting to src/main/resources. */
    private List<File> resourceRoots() {
        List<File> roots = new ArrayList<>();
        List<String> configured = config.getMainResourcePaths();
        if (configured == null || configured.isEmpty()) {
            File conventional = new File(projectRoot, "src/main/resources");
            if (conventional.isDirectory()) {
                roots.add(conventional);
            }
            return roots;
        }
        for (String path : configured) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            File dir = new File(path);
            roots.add(dir.isAbsolute() ? dir : new File(projectRoot, path));
        }
        return roots;
    }

    /** Property values for opt-in resource filtering (section 22). */
    private Map<String, String> filterProperties() {
        Map<String, String> props = new java.util.LinkedHashMap<>(config.getProperties());
        props.putIfAbsent("project.name", config.getName());
        props.putIfAbsent("project.version", config.getVersion());
        props.putIfAbsent("project.group", config.getGroup());
        return props;
    }

    private CompilationResult compileSources(ProjectAnalysis analysis, 
                                           DependencyResolution dependencyResult) {
        List<Path> sourceFiles = analysis.getSourceFiles();

        // Batch compilation in a single javac invocation: Java sources reference
        // each other, so per-file (parallel or sequential) compilation fails with
        // unresolved symbols and spawns one JVM per file.
        // Absolute path: the JVM working directory can differ between runs
        // (e.g. Surefire sets CWD to target/ during tests), so a relative
        // output path would produce nested/wrong class locations.
        // Compiled classes go to <buildBase>/classes (target/classes),
        // matching the Maven convention and the default configuration value.
        String outputDir = resolveClassesDir().toString();
        return compiler.compileBatch(sourceFiles, dependencyResult, cache, outputDir);
    }

    /**
     * Resolve the compile classpath (compile + provided scopes) through Maven
     * Resolver, per AGENT.md section 2. Scope filtering keeps test and runtime
     * dependencies off the main compile classpath.
     */
    private DependencyResolution resolveWithAether() {
        return io.github.yasmramos.forge.core.resolver.AetherResolution.resolve(
            config, DependencyScopes.COMPILE, null);
    }

    /**
     * Resolve the compiled-classes directory (build base + "classes",
     * i.e. target/classes by Maven convention). Always absolute so that
     * results do not depend on the process working directory.
     */
    private Path resolveClassesDir() {
        return resolveBuildBaseDir().resolve("classes");
    }

    /**
     * Resolve the build base directory (Maven convention: "target"). The
     * configured output directory is the compiled-classes directory
     * (target/classes); its parent is used as the build base so that all
     * derived paths (classes/, forge-output/) stay consistent.
     */
    private Path resolveBuildBaseDir() {
        String configured = config.getOutputDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            Path classes = Paths.get(configured).toAbsolutePath().normalize();
            Path parent = classes.getParent();
            return parent != null ? parent : classes;
        }
        return Paths.get("target").toAbsolutePath().normalize();
    }

    /**
     * Resolve the main source directory from configuration, falling back to "src/main/java".
     */
    private Path resolveSourceDir() {
        String configured = config.getSourceDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured);
        }
        return Paths.get("src", "main", "java");
    }

    private PackageResult packageArtifacts(CompilationResult compilationResult) {
        logger.info("Packaging compiled artifacts...");
        
        try {
            // Create output directory based on project configuration
            Path outputDir = resolveBuildBaseDir().resolve("forge-output");
            Files.createDirectories(outputDir);
            
            int artifactCount = 0;
            
            // Package main JAR
            Path mainJar = packageMainJar(outputDir);
            if (mainJar != null) {
                artifactCount++;
                logger.info("Main JAR packaged: " + mainJar);
            }
            
            // Package additional artifacts (sources, javadoc, etc.)
            artifactCount += packageAdditionalArtifacts(outputDir);
            
            logger.info("Packaging complete: " + artifactCount + " artifacts created");
            return new PackageResult(true, "jar", artifactCount);
            
        } catch (Exception e) {
            logger.error("Packaging failed", e);
            return new PackageResult(false, "jar", 0);
        }
    }
    
    private Path packageMainJar(Path outputDir) {
        try {
            Path classesDir = resolveClassesDir();
            if (!Files.exists(classesDir)) {
                logger.warn("No classes directory found for JAR packaging");
                return null;
            }

            Path jarFile = outputDir.resolve("forge-main.jar");

            // Deterministic packaging (AGENT.md decision 17): the external
            // 'jar' tool stamps filesystem mtimes, so we write the archive
            // ourselves with a fixed entry timestamp and sorted entries.
            io.github.yasmramos.forge.core.packageinfo.JarWriter.writeDirectory(
                classesDir, jarFile, artifactEpochSeconds());
            logger.info("Main JAR created successfully");
            return jarFile;

        } catch (Exception e) {
            logger.error("Error creating main JAR", e);
            return null;
        }
    }

    /** Fixed timestamp for reproducible artifacts (AGENT.md decision 17). */
    private long artifactEpochSeconds() {
        return io.github.yasmramos.forge.core.packageinfo.ReproducibleBuild.epochSeconds(
            projectRoot, config.getBuildSettings().getReproducibleTimestamp());
    }
    
    private int packageAdditionalArtifacts(Path outputDir) {
        int count = 0;
        
        try {
            // Create sources JAR if source files exist (honoring configured source directory)
            Path sourcesDir = resolveSourceDir();
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
            logger.error("Error creating additional artifacts", e);
        }
        
        return count;
    }
    
    private boolean createSourcesJar(Path sourcesDir, Path jarFile) {
        try {
            io.github.yasmramos.forge.core.packageinfo.JarWriter.writeDirectory(
                sourcesDir, jarFile, artifactEpochSeconds());
            return true;
        } catch (Exception e) {
            logger.error("Error creating sources JAR", e);
            return false;
        }
    }
    
    private boolean generateJavadoc(Path outputDir) {
        try {
            Path docDir = outputDir.resolve("javadoc");
            Files.createDirectories(docDir);
            
            // Generate javadoc for main sources (honoring configured source directory)
            Path sourcesDir = resolveSourceDir();
            if (!Files.exists(sourcesDir)) {
                return false;
            }
            
            ProcessBuilder pb = new ProcessBuilder(
                "javadoc", "-quiet", "-d", docDir.toString(), 
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
            logger.error("Error generating javadoc", e);
            return false;
        }
    }
    
    private TestResult executeTests(PackageResult packageResult) {
        logger.info("Running tests...");
        io.github.yasmramos.forge.core.test.TestExecutor.Result result =
            io.github.yasmramos.forge.core.test.TestExecutor.execute(
                config, projectRoot, resolveClassesDir(),
                resolveBuildBaseDir().resolve("test-classes"));
        logger.info("Test execution complete: " + (result.isSuccess() ? "all tests passed"
            : "tests failed"));
        return new TestResult(result.isSuccess(), result.getTotal(), result.getPassed());
    }
    
    private boolean runSingleTest(Path testFile) {
        try {
            // Simple test execution - compile and run if it has a main method
            String fileName = testFile.getFileName().toString();
            String className = fileName.substring(0, fileName.length() - 5); // Remove .java
            
            logger.debug("Running test: " + className);
            
            // Check if test has a main method
            String content = Files.readString(testFile);
            if (!content.contains("public static void main")) {
                logger.debug("Skipping test (no main method): " + className);
                return true; // Skip tests without main method
            }
            
            // Compile test (classpath derived from configured output directory)
            String testClasspath = buildTestCompileClasspath();
            ProcessBuilder compilePb = new ProcessBuilder(
                "javac", "-cp", testClasspath,
                testFile.toString()
            );
            
            Process compileProcess = compilePb.start();
            int compileExit = compileProcess.waitFor();
            
            if (compileExit != 0) {
                logger.error("Test compilation failed: " + className);
                return false;
            }
            
            // Run test
            ProcessBuilder runPb = new ProcessBuilder(
                "java", "-cp", testClasspath + File.pathSeparator + ".",
                className
            );
            
            Process runProcess = runPb.start();
            int runExit = runProcess.waitFor();
            
            return runExit == 0;
            
        } catch (Exception e) {
            logger.error("Error running test: " + testFile, e);
            return false;
        }
    }
    
    /**
     * Build the classpath used for compiling and running tests,
     * based on the configured output directory.
     */
    private String buildTestCompileClasspath() {
        Path base = resolveBuildBaseDir();
        return resolveClassesDir() + File.pathSeparator
            + base.resolve("forge-output").resolve("forge-main.jar");
    }

    private void shutdown() {
        // Shut down the compiler's internal executor first to avoid thread leaks
        compiler.shutdown();

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
        private final io.github.yasmramos.forge.cli.error.ForgeException error;

        public BuildResult(boolean success, PackageResult packageResult, TestResult testResult) {
            this(success, packageResult, testResult, null);
        }

        public BuildResult(boolean success, PackageResult packageResult, TestResult testResult,
                           io.github.yasmramos.forge.cli.error.ForgeException error) {
            this.success = success;
            this.packageResult = packageResult;
            this.testResult = testResult;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public PackageResult getPackageResult() { return packageResult; }
        public TestResult getTestResult() { return testResult; }

        /**
         * Structured failure when the build aborted on a ForgeError; null when
         * the build failed for a non-structured reason or succeeded. Lets the
         * CLI render it with the user's {@code --format=json}/{@code --debug}
         * flags (AGENT.md 10bis) instead of a bare "build failed" line.
         */
        public io.github.yasmramos.forge.cli.error.ForgeException getError() { return error; }
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