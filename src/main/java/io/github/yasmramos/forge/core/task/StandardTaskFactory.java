package io.github.yasmramos.forge.core.task;

import io.github.yasmramos.forge.core.BuildAnalyzer;
import io.github.yasmramos.forge.core.Compiler;
import io.github.yasmramos.forge.core.DependencyResolver;
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

    public StandardTaskFactory(ProjectConfig config, File projectRoot, Path classesDir) {
        this.config = config;
        this.projectRoot = projectRoot;
        this.classesDir = classesDir.toAbsolutePath().normalize();
    }

    /** Creates the compile task: sources + dependencies in, classes out. */
    public ForgeTask compile() {
        TaskType type = new TaskTypeRegistry().get(TaskType.COMPILE);
        ForgeTask task = new ForgeTask(COMPILE, type)
            .input(new TaskInput.FilesInput("main-sources", sourceRoots()))
            .input(new TaskInput.ValueInput("dependencies", String.valueOf(config.getDependencies())))
            .input(new TaskInput.ValueInput("toolchain", String.valueOf(config.getSourceCompatibility())))
            .output(new TaskOutput("classes", Collections.singletonList(relative(classesDir))));

        task.action(context -> {
            BuildAnalyzer analyzer = new BuildAnalyzer();
            ProjectAnalysis analysis = analyzer.analyzeProject(config);
            DependencyResolver resolver = new DependencyResolver();
            DependencyResolution resolution = resolver.resolve(config.getDependencies(), analysis);

            Compiler compiler = new Compiler();
            compiler.setClasspath(System.getProperty("java.class.path"));
            CompilationResult result =
                compiler.compileBatch(analysis.getSourceFiles(), resolution, null, classesDir.toString());
            if (!result.isSuccess()) {
                return TaskResult.failure("Compilation failed: " + result.getCompiledFiles()
                    + "/" + result.getTotalFiles() + " files compiled successfully."
                    + " Check the compiler messages above for the offending sources.");
            }
            compiler.shutdown();
            context.put(COMPILE + ".compiledFiles", result.getCompiledFiles());
            return TaskResult.success("Compiled " + result.getCompiledFiles() + " source files");
        });
        return task;
    }

    /**
     * Creates the package task. It hard-depends on compile (its outputs feed
     * the JAR) and declares the produced JAR as a relocatable output.
     */
    public ForgeTask packageTask() {
        TaskType type = new TaskTypeRegistry().get(TaskType.PACKAGE);
        Path jarFile = classesDir.getParent().resolve("forge-output").resolve("forge-main.jar");
        ForgeTask task = new ForgeTask(PACKAGE, type)
            .dependsOn(COMPILE)
            .input(new TaskInput.ValueInput("upstream:compile", COMPILE))
            .output(new TaskOutput("jar", Collections.singletonList(relative(jarFile))));

        task.action(context -> {
            try {
                java.nio.file.Files.createDirectories(jarFile.getParent());
                ProcessBuilder pb = new ProcessBuilder(
                    "jar", "cf", jarFile.toString(), "-C", classesDir.toString(), ".");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int code = p.waitFor();
                if (code != 0 || !java.nio.file.Files.isRegularFile(jarFile)) {
                    return TaskResult.failure("Packaging failed with exit code " + code
                        + ". Ensure the 'jar' tool is available and classes were compiled first.");
                }
                return TaskResult.success("Packaged " + jarFile.getFileName());
            } catch (Exception e) {
                return TaskResult.failure("Packaging failed: " + e.getMessage()
                    + ". Verify the JDK bin directory is on PATH.");
            }
        });
        return task;
    }

    /**
     * Creates the test task placeholder. Test execution wiring reuses the
     * engine's existing runner once it is exposed through a stable API; until
     * then the task is ordered after compile via a soft dependency and is a
     * no-op that always succeeds, so graphs including it stay honest about
     * what actually ran (AGENT.md decision 10).
     */
    public ForgeTask test() {
        TaskType type = new TaskTypeRegistry().get(TaskType.TEST);
        ForgeTask task = new ForgeTask(TEST, type)
            .softDependsOn(COMPILE)
            .input(new TaskInput.FilesInput("test-sources", testSourceRoots()));
        task.action(context -> TaskResult.success("No test runner bound yet; skipped"));
        return task;
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
