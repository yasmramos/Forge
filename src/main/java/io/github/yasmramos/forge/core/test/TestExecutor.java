package io.github.yasmramos.forge.core.test;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.core.SourceDependencies;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compiles and runs a project's JUnit tests, returning a structured result.
 *
 * <p>Extracted from {@code ForgeEngine} so that both the phased build and the
 * {@code test} task of the task graph (AGENT.md decision 1) share one
 * implementation: test execution is the same operation regardless of which
 * pipeline requested it, and duplicating it would let the two drift.</p>
 *
 * <p>Test sources are resolved against the project root, never the process
 * working directory, so a build driven from elsewhere behaves identically.</p>
 *
 * <p>Two opt-in behaviours of section 21 live here, both off by default:
 * <b>selective execution</b> ({@code [test] selective}) runs only the tests a
 * change can affect, derived from the source dependency graph, and
 * <b>flaky retry</b> ({@code [test] retry_failed}) re-runs a failing class a few
 * times. Selection is computed here, not by the callers, so both pipelines
 * choose the same set and write the same state.</p>
 */
public final class TestExecutor {

    /** JUnit Platform launcher version used to run Jupiter tests in-process. */
    private static final String JUNIT_LAUNCHER = "org.junit.platform:junit-platform-launcher:1.9.2";

    private TestExecutor() {
    }

    /** Outcome of a test run: success flag plus found/passed counts. */
    public static final class Result {
        private final boolean success;
        private final int total;
        private final int passed;
        private final int skipped;
        private final boolean selected;
        private final List<String> flaky;

        public Result(boolean success, int total, int passed) {
            this(success, total, passed, 0, false, Collections.emptyList());
        }

        public Result(boolean success, int total, int passed, int skipped,
                      boolean selected, List<String> flaky) {
            this.success = success;
            this.total = total;
            this.passed = passed;
            this.skipped = skipped;
            this.selected = selected;
            this.flaky = flaky == null ? Collections.emptyList() : flaky;
        }

        public boolean isSuccess() { return success; }
        public int getTotal() { return total; }
        public int getPassed() { return passed; }
        /** Tests not run because a change could not reach them (section 21). */
        public int getSkipped() { return skipped; }
        /** True when selective execution narrowed the set. */
        public boolean isSelected() { return selected; }
        /** Classes that failed then passed on retry, reported, never hidden. */
        public List<String> getFlaky() { return flaky; }
    }

    /**
     * Runs every test source under the project's test directory, or only the
     * affected ones when selective execution is enabled.
     *
     * @param config        parsed forge.toml (dependencies, repositories, [test])
     * @param projectRoot   directory holding the project; test sources are
     *                      looked up relative to it
     * @param classesDir    compiled main classes (test classpath entry)
     * @param testClassesDir output directory for compiled test classes
     * @return a result; a project without test sources succeeds with 0 tests
     */
    public static Result execute(ProjectConfig config, File projectRoot,
                                 Path classesDir, Path testClassesDir) {
        try {
            List<Path> testFiles = findTestFiles(projectRoot);
            if (testFiles.isEmpty()) {
                return new Result(true, 0, 0);
            }

            ProjectConfig.TestSettings testSettings = config.getTestSettings();
            boolean selective = testSettings != null && testSettings.isSelective();
            List<Path> mainFiles = findMainFiles(config, projectRoot);
            List<Path> resourceDirs = resourceDirs(config, projectRoot);

            List<Path> toRun = testFiles;
            SelectiveTests.Selection selection = null;
            if (selective) {
                selection = SelectiveTests.select(projectRoot, mainFiles, testFiles, resourceDirs);
                toRun = selection.getAffected();
                if (toRun.isEmpty()) {
                    // Nothing a change can reach: record the green state and stop.
                    SelectiveTests.record(projectRoot, mainFiles, testFiles, resourceDirs);
                    return new Result(true, 0, 0, testFiles.size(), true, Collections.emptyList());
                }
            }

            Files.createDirectories(testClassesDir);

            // Compile every test source (a complete test-classpath), then run
            // only the selected classes; compiling a handful more is cheap and
            // avoids a partial-compile classpath.
            List<String> runClasses = new ArrayList<>();
            if (selective && !selection.isAll()) {
                for (Path test : toRun) {
                    runClasses.add(SourceDependencies.fullyQualifiedName(test));
                }
            }

            StringBuilder cp = new StringBuilder();
            cp.append(classesDir).append(File.pathSeparator).append(testClassesDir);
            for (String jar : resolveTestDependencyJars(config)) {
                cp.append(File.pathSeparator).append(jar);
            }
            String classpath = cp.toString();

            ProcessBuilder compilePb = new ProcessBuilder(
                "javac", "-cp", classpath, "-d", testClassesDir.toString());
            compilePb.redirectErrorStream(true);
            for (Path testFile : testFiles) {
                compilePb.command().add(testFile.toString());
            }
            Process compileProcess = compilePb.start();
            String compileOut = new String(compileProcess.getInputStream().readAllBytes());
            if (compileProcess.waitFor() != 0) {
                System.err.println(compileOut);
                return new Result(false, testFiles.size(), 0);
            }

            int retries = testSettings == null ? 0 : testSettings.getRetryFailed();

            // Runner lives in Forge's own jar, so append it to the child cp.
            String selfJar = io.github.yasmramos.forge.Forge.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            List<String> command = new ArrayList<>();
            command.add("java");
            command.add("-cp");
            command.add(classpath + File.pathSeparator + selfJar);
            command.add("io.github.yasmramos.forge.core.test.JUnitRunner");
            command.add(testClassesDir.toString());
            if (retries > 0) {
                command.add("--retry=" + retries);
            }
            command.addAll(runClasses);

            ProcessBuilder runPb = new ProcessBuilder(command);
            runPb.redirectErrorStream(true);
            Process runProcess = runPb.start();
            String runOut = new String(runProcess.getInputStream().readAllBytes());
            int exitCode = runProcess.waitFor();
            System.out.println(runOut);

            boolean passed = exitCode == 0;
            List<String> flaky = parseFlaky(runOut);

            if (passed && selective) {
                // Only a fully green run updates the baseline, so a failure is
                // never remembered as a pass (section 21).
                SelectiveTests.record(projectRoot, mainFiles, testFiles, resourceDirs);
            }

            int ran = selective ? toRun.size() : testFiles.size();
            int skipped = testFiles.size() - ran;
            return new Result(passed, ran, passed ? ran : 0, skipped, selective, flaky);
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return new Result(false, 0, 0);
        }
    }

    /** Classes the runner reported as failing then passing on retry. */
    private static List<String> parseFlaky(String output) {
        List<String> flaky = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("FORGE-TESTS-FLAKY ")) {
                for (String name : line.substring("FORGE-TESTS-FLAKY ".length()).split(",")) {
                    if (!name.trim().isEmpty()) {
                        flaky.add(name.trim());
                    }
                }
            }
        }
        return flaky;
    }

    /**
     * Main source files under the configured source directory (or
     * {@code src/main/java}), the half of the graph a test can depend on.
     */
    static List<Path> findMainFiles(ProjectConfig config, File projectRoot) {
        String configured = config.getSourceDirectory();
        Path dir = (configured != null && !configured.trim().isEmpty())
            ? Paths.get(configured)
            : projectRoot.toPath().resolve(Paths.get("src", "main", "java"));
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (Exception ignored) {
                // Unreadable main tree: selection falls back to running all.
            }
        }
        return files;
    }

    /** Main and test resource directories, for the resource-change guard. */
    static List<Path> resourceDirs(ProjectConfig config, File projectRoot) {
        List<Path> dirs = new ArrayList<>();
        List<String> main = config.getMainResourcePaths();
        if (main == null || main.isEmpty()) {
            Path conventional = projectRoot.toPath().resolve(Paths.get("src", "main", "resources"));
            if (Files.isDirectory(conventional)) {
                dirs.add(conventional);
            }
        } else {
            for (String path : main) {
                addDir(dirs, projectRoot, path);
            }
        }
        List<String> test = config.getTestResourcePaths();
        if (test != null) {
            for (String path : test) {
                addDir(dirs, projectRoot, path);
            }
        }
        return dirs;
    }

    private static void addDir(List<Path> dirs, File projectRoot, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        Path dir = Paths.get(path);
        if (!dir.isAbsolute()) {
            dir = projectRoot.toPath().resolve(dir);
        }
        if (Files.isDirectory(dir)) {
            dirs.add(dir);
        }
    }

    /**
     * Test sources under {@code src/test/java}, {@code test} or {@code src/test}
     * (relative to the project root), keeping files whose name contains
     * "test" or "spec".
     */
    static List<Path> findTestFiles(File projectRoot) {
        // A Set, not a List: the candidate directories overlap (src/test/java
        // lives under src/test), and a test counted twice would be run twice.
        java.util.Set<Path> testFiles = new java.util.LinkedHashSet<>();
        Path base = projectRoot.toPath();
        Path[] testDirs = {
            base.resolve(Paths.get("src", "test", "java")),
            base.resolve("test"),
            base.resolve(Paths.get("src", "test"))
        };
        try {
            for (Path testDir : testDirs) {
                if (Files.exists(testDir)) {
                    Files.walk(testDir)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            return name.contains("test") || name.contains("spec");
                        })
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(testFiles::add);
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding test files: " + e.getMessage());
        }
        return new ArrayList<>(testFiles);
    }

    /**
     * Absolute paths of every test-scope dependency plus the JUnit Platform
     * launcher, resolved through the shared Aether adapter (AGENT.md section 2).
     * Best-effort: a resolution failure yields an empty list so the caller still
     * compiles against the main classes and reports the real compiler error.
     */
    private static List<String> resolveTestDependencyJars(ProjectConfig config) {
        List<String> jars = new ArrayList<>();
        try {
            io.github.yasmramos.forge.model.DependencyResolution resolution =
                io.github.yasmramos.forge.core.resolver.AetherResolution.resolve(
                    config, DependencyScopes.TEST,
                    Collections.singletonList(JUNIT_LAUNCHER));
            for (io.github.yasmramos.forge.model.DependencyInfo d : resolution.getDependencies()) {
                jars.add(d.getLocalFile().getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Test dependency resolution failed (" + e.getMessage()
                + "); tests will run without resolved dependencies.");
        }
        return jars;
    }
}
