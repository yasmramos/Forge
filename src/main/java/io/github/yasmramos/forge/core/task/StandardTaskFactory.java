package io.github.yasmramos.forge.core.task;

import io.github.yasmramos.forge.core.BuildAnalyzer;
import io.github.yasmramos.forge.core.Compiler;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds the standard task graph for a project (AGENT.md decision 1): every
 * built-in phase (compile, test, package) becomes a {@link ForgeTask} whose
 * inputs and outputs are declared explicitly so hashing and caching work
 * correctly. Actions delegate to the existing engine components, keeping a
 * single source of truth for the actual build logic while the graph layer
 * provides dependency ordering, parallelism and content-based caching.
 */
public class StandardTaskFactory {

    public static final String COMPILE = "compile";
    public static final String TEST = "test";
    public static final String PACKAGE = "package";

    private final ProjectConfig config;
    private final File projectRoot;
    private final Path classesDir;
    private BuildEnvironment environment;

    public StandardTaskFactory(ProjectConfig config, File projectRoot, Path classesDir) {
        this.config = config;
        this.projectRoot = projectRoot;
        this.classesDir = classesDir.toAbsolutePath().normalize();
        this.environment = BuildEnvironment.forProject(config.getPlugins());
    }

    /**
     * Replaces the build environment, e.g. to fold the identity of the plugins
     * actually loaded (not just those declared) into the task cache key
     * (AGENT.md sections 3 and 4bis). Must be called before tasks are created.
     */
    public void setEnvironment(BuildEnvironment environment) {
        this.environment = environment;
    }

    /** Creates the compile task: sources + dependencies in, classes out. */
    public ForgeTask compile() {
        TaskType type = new TaskTypeRegistry().get(TaskType.COMPILE);
        ForgeTask task = new ForgeTask(COMPILE, type)
            .input(new TaskInput.FilesInput("main-sources", sourceRoots()))
            .input(new TaskInput.FilesInput("main-resources", resourceRoots()))
            .input(new TaskInput.ValueInput("dependencies", String.valueOf(
                io.github.yasmramos.forge.core.DependencyScopes.filter(
                    config.getDependencies(),
                    io.github.yasmramos.forge.core.DependencyScopes.COMPILE))))
            .input(new TaskInput.ValueInput("toolchain", String.valueOf(config.getSourceCompatibility())))
            .input(new TaskInput.ValueInput("resource-filtering", String.valueOf(
                config.getBuildSettings().isResourceFiltering())))
            .input(new TaskInput.ValueInput("processors", String.valueOf(config.getProcessors())))
            .output(new TaskOutput("classes", Collections.singletonList(relative(classesDir))));

        task.action(context -> {
            BuildAnalyzer analyzer = new BuildAnalyzer();
            ProjectAnalysis analysis = analyzer.analyzeProject(config);
            // Only compile-scope deps belong on the main compile classpath;
            // resolution goes through Maven Resolver (AGENT.md section 2).
            DependencyResolution resolution =
                io.github.yasmramos.forge.core.resolver.AetherResolution.resolve(
                    config,
                    io.github.yasmramos.forge.core.DependencyScopes.COMPILE,
                    null);

            Compiler compiler = new Compiler();
            compiler.setProjectId(config.getGroup() + ":" + config.getName());
            compiler.setClasspath(System.getProperty("java.class.path"));
            compiler.setProcessorNames(config.getProcessors());
            CompilationResult result =
                compiler.compileBatch(analysis.getSourceFiles(), resolution, null, classesDir.toString());
            if (!result.isSuccess()) {
                return TaskResult.failure("Compilation failed: " + result.getCompiledFiles()
                    + "/" + result.getTotalFiles() + " files compiled successfully."
                    + " Check the compiler messages above for the offending sources.");
            }
            compiler.shutdown();

            // Resources into the classes dir so the packaged jar carries them
            // (AGENT.md section 22); the same inputs are declared above, so a
            // resource change re-runs this task instead of restoring stale classes.
            try {
                boolean filtering = config.getBuildSettings().isResourceFiltering();
                io.github.yasmramos.forge.core.resource.ResourceProcessor.copy(
                    resourceRoots(), classesDir, filtering, config.getProperties());
            } catch (java.io.IOException e) {
                return TaskResult.failure("Resource copy failed: " + e.getMessage());
            }

            context.put(COMPILE + ".compiledFiles", result.getCompiledFiles());
            return TaskResult.success("Compiled " + result.getCompiledFiles() + " source files");
        });
        return task.environment(environment);
    }

    /**
     * Creates the package task. It hard-depends on compile (its outputs feed
     * the JAR) and declares the produced JAR as a relocatable output.
     *
     * <p>The dependency is expressed only through {@code dependsOn(TEST)}:
     * tests must pass before an artifact is packaged (Maven ordering), and the
     * scheduler folds the test task's content hash — which itself folds the
     * compile task's — into this task's cache key (see
     * {@link ForgeTask#computeHash(java.util.Map)}), so a changed source re-runs
     * packaging instead of restoring a stale JAR from cache.</p>
     */
    public ForgeTask packageTask() {
        TaskType type = new TaskTypeRegistry().get(TaskType.PACKAGE);
        Path jarFile = classesDir.getParent().resolve("forge-output").resolve("forge-main.jar");
        ForgeTask task = new ForgeTask(PACKAGE, type)
            .dependsOn(TEST)
            .output(new TaskOutput("jar", Collections.singletonList(relative(jarFile))));

        task.action(context -> {
            try {
                // Deterministic packaging (AGENT.md decision 17): a fixed entry
                // timestamp plus sorted entries make the JAR byte-identical
                // across runs and machines.
                long epoch = io.github.yasmramos.forge.core.packageinfo.ReproducibleBuild
                    .epochSeconds(projectRoot, config.getBuildSettings().getReproducibleTimestamp());
                io.github.yasmramos.forge.core.packageinfo.JarWriter.writeDirectory(
                    classesDir, jarFile, epoch);
                return TaskResult.success("Packaged " + jarFile.getFileName());
            } catch (Exception e) {
                return TaskResult.failure("Packaging failed: " + e.getMessage()
                    + ". Verify the compiled classes exist under " + classesDir + ".");
            }
        });
        return task.environment(environment);
    }

    /**
     * Creates the test task: test sources in, test execution out. It hard-depends
     * on compile (tests run against the freshly compiled main classes, and the
     * scheduler folds compile's hash into this task's key), so a source change
     * re-runs the tests instead of restoring a stale result from cache.
     *
     * <p>Execution is delegated to {@link io.github.yasmramos.forge.core.test.TestExecutor},
     * the same component the phased build uses, so both pipelines agree on what
     * "running the tests" means.</p>
     */
    public ForgeTask test() {
        TaskType type = new TaskTypeRegistry().get(TaskType.TEST);
        Path testClassesDir = classesDir.getParent().resolve("test-classes");
        ForgeTask task = new ForgeTask(TEST, type)
            .dependsOn(COMPILE)
            .input(new TaskInput.FilesInput("test-sources", testSourceRoots()))
            .input(new TaskInput.ValueInput("test-dependencies", String.valueOf(
                io.github.yasmramos.forge.core.DependencyScopes.filter(
                    config.getDependencies(),
                    io.github.yasmramos.forge.core.DependencyScopes.TEST))))
            .output(new TaskOutput("test-classes", Collections.singletonList(relative(testClassesDir))));

        task.action(context -> {
            io.github.yasmramos.forge.core.test.TestExecutor.Result result =
                io.github.yasmramos.forge.core.test.TestExecutor.execute(
                    config, projectRoot, classesDir, testClassesDir);
            if (!result.isSuccess()) {
                return TaskResult.failure("Tests failed: " + result.getPassed()
                    + "/" + result.getTotal() + " passed. See the JUnit output above"
                    + " for the failing test(s).");
            }
            context.put(TEST + ".total", result.getTotal());
            context.put(TEST + ".passed", result.getPassed());
            return TaskResult.success(result.getTotal() == 0
                ? "No test sources found"
                : "Ran " + result.getTotal() + " test class(es), all passed");
        });
        return task.environment(environment);
    }

    // ---- helpers ----------------------------------------------------------

    private List<File> sourceRoots() {
        String configured = config.getSourceDirectory();
        Path dir = (configured != null && !configured.trim().isEmpty())
            ? new File(configured).toPath()
            : new File(projectRoot, "src/main/java").toPath();
        return Collections.singletonList(dir.toFile());
    }

    private List<File> testSourceRoots() {
        return Collections.singletonList(new File(projectRoot, "src/test/java"));
    }

    /** Declared resource directories, defaulting to src/main/resources (section 22). */
    private List<File> resourceRoots() {
        List<String> configured = config.getMainResourcePaths();
        if (configured == null || configured.isEmpty()) {
            File conventional = new File(projectRoot, "src/main/resources");
            return conventional.isDirectory()
                ? Collections.singletonList(conventional)
                : Collections.emptyList();
        }
        List<File> roots = new java.util.ArrayList<>();
        for (String path : configured) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            File dir = new File(path);
            roots.add(dir.isAbsolute() ? dir : new File(projectRoot, path));
        }
        return roots;
    }

    /** Project-relative POSIX path for cache-portable outputs. */
    private String relative(Path absolute) {
        Path root = projectRoot.toPath().toAbsolutePath().normalize();
        Path abs = absolute.toAbsolutePath().normalize();
        try {
            return root.relativize(abs).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            // Different filesystem roots (e.g. temp dir outside project): keep
            // the absolute form so the task still runs, just without portable caching.
            return abs.toString();
        }
    }
}
