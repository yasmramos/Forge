package io.github.yasmramos.forge.core.test;

import io.github.yasmramos.forge.core.DependencyScopes;
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

        public Result(boolean success, int total, int passed) {
            this.success = success;
            this.total = total;
            this.passed = passed;
        }

        public boolean isSuccess() { return success; }
        public int getTotal() { return total; }
        public int getPassed() { return passed; }
    }

    /**
     * Runs every test source under the project's test directory.
     *
     * @param config        parsed forge.toml (dependencies, repositories)
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

            Files.createDirectories(testClassesDir);

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

            // Runner lives in Forge's own jar, so append it to the child cp.
            String selfJar = io.github.yasmramos.forge.Forge.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            ProcessBuilder runPb = new ProcessBuilder(
                "java", "-cp", classpath + File.pathSeparator + selfJar,
                "io.github.yasmramos.forge.core.test.JUnitRunner",
                testClassesDir.toString());
            runPb.redirectErrorStream(true);
            Process runProcess = runPb.start();
            String runOut = new String(runProcess.getInputStream().readAllBytes());
            int exitCode = runProcess.waitFor();
            System.out.println(runOut);

            boolean passed = exitCode == 0;
            return new Result(passed, testFiles.size(), passed ? testFiles.size() : 0);
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return new Result(false, 0, 0);
        }
    }

    /**
     * Test sources under {@code src/test/java}, {@code test} or {@code src/test}
     * (relative to the project root), keeping files whose name contains
     * "test" or "spec".
     */
    static List<Path> findTestFiles(File projectRoot) {
        List<Path> testFiles = new ArrayList<>();
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
                        .forEach(testFiles::add);
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding test files: " + e.getMessage());
        }
        return testFiles;
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
