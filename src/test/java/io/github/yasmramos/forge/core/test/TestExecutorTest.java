package io.github.yasmramos.forge.core.test;

import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link TestExecutor} (AGENT.md section 21): a real
 * {@code javac} compilation and a real JUnit Platform run in a child JVM, on a
 * throwaway project. These exercise the whole path — source discovery, test
 * dependency resolution, the subprocess runner, and the selection state — so
 * the feature is validated where it actually runs, not only in isolation.
 *
 * <p>They hit Maven Central to resolve JUnit; the surrounding suite does the
 * same (see {@code AetherResolutionTest}), so the requirement is not new.</p>
 */
class TestExecutorTest {

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes());
    }

    private static void compileMain(Path root, Path classesDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Files.createDirectories(classesDir);
        Path source = root.resolve("src/main/java/p/Calc.java");
        int rc = compiler.run(null, null, null,
            "-d", classesDir.toString(), source.toString());
        assertEquals(0, rc, "main sources must compile");
    }

    private static ProjectConfig config(boolean selective) {
        ProjectConfig config = new ProjectConfig();
        config.setName("selective-test");
        config.setVersion("1.0.0");
        Map<String, Object> deps = new LinkedHashMap<>();
        Map<String, Object> junit = new LinkedHashMap<>();
        junit.put("version", "5.9.2");
        junit.put("scope", "test");
        deps.put("org.junit.jupiter:junit-jupiter", junit);
        config.setDependencies(deps);
        config.getTestSettings().setSelective(selective);
        return config;
    }

    private static void seedProject(Path root, String calcBody, String testBody) throws Exception {
        write(root, "src/main/java/p/Calc.java",
            "package p; public class Calc { public int value() { " + calcBody + " } }");
        write(root, "src/test/java/p/CalcTest.java",
            "package p;\n"
            + "import org.junit.jupiter.api.Test;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n"
            + "public class CalcTest {\n"
            + "  @Test void checks() { " + testBody + " }\n"
            + "}\n");
    }

    @Test
    void runsTestsWithTheFullPipelineWhenSelectionIsOff(@TempDir Path tmp) throws Exception {
        seedProject(tmp, "return 42;", "assertEquals(42, new Calc().value());");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);

        TestExecutor.Result result = TestExecutor.execute(
            config(false), tmp.toFile(), classes, tmp.resolve("target/test-classes"));

        assertTrue(result.isSuccess(), "the seeded test passes");
        assertEquals(1, result.getTotal());
        assertFalse(result.isSelected(), "selection is off, so nothing is skipped");
        assertEquals(0, result.getSkipped());
    }

    @Test
    void firstSelectiveRunEstablishesBaselineThenSkips(@TempDir Path tmp) throws Exception {
        seedProject(tmp, "return 42;", "assertEquals(42, new Calc().value());");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);
        Path testClasses = tmp.resolve("target/test-classes");

        // First selective run: no baseline, so the test runs and is recorded.
        TestExecutor.Result first = TestExecutor.execute(
            config(true), tmp.toFile(), classes, testClasses);
        assertTrue(first.isSuccess());
        assertEquals(1, first.getTotal());
        assertTrue(Files.isRegularFile(tmp.resolve(".forge/test-state.toml")),
            "a green run records the baseline");

        // Nothing changed: the test is skipped, and the build still succeeds.
        TestExecutor.Result second = TestExecutor.execute(
            config(true), tmp.toFile(), classes, testClasses);
        assertTrue(second.isSuccess(), "nothing to run is a success, not a failure");
        assertEquals(0, second.getTotal());
        assertEquals(1, second.getSkipped());
        assertTrue(second.isSelected());
    }

    @Test
    void changingMainSourceRerunsTheAffectedTest(@TempDir Path tmp) throws Exception {
        seedProject(tmp, "return 42;", "assertEquals(42, new Calc().value());");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);
        Path testClasses = tmp.resolve("target/test-classes");

        TestExecutor.execute(config(true), tmp.toFile(), classes, testClasses);

        // Change the main source so its effective hash changes; the test must
        // re-run. Recompile main so the child JVM sees the new bytecode.
        seedProject(tmp, "return 43;", "assertEquals(43, new Calc().value());");
        compileMain(tmp, classes);

        TestExecutor.Result after = TestExecutor.execute(
            config(true), tmp.toFile(), classes, testClasses);
        assertEquals(1, after.getTotal(), "the affected test re-runs");
        assertTrue(after.isSuccess());
    }

    @Test
    void failingTestsAreReportedAndNotRememberedAsPassing(@TempDir Path tmp) throws Exception {
        seedProject(tmp, "return 42;", "assertEquals(99, new Calc().value());");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);
        Path testClasses = tmp.resolve("target/test-classes");

        TestExecutor.Result result = TestExecutor.execute(
            config(true), tmp.toFile(), classes, testClasses);

        assertFalse(result.isSuccess(), "a failing test must fail the build");
        assertFalse(Files.exists(tmp.resolve(".forge/test-state.toml")),
            "a failed run must not write a baseline (section 21)");
    }

    @Test
    void projectWithoutTestsSucceeds(@TempDir Path tmp) throws Exception {
        write(tmp, "src/main/java/p/Calc.java", "package p; public class Calc {}");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);

        TestExecutor.Result result = TestExecutor.execute(
            config(true), tmp.toFile(), classes, tmp.resolve("target/test-classes"));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getTotal());
    }

    @Test
    void testFilesAreDiscoveredUnderTheProjectNotTheWorkingDirectory(@TempDir Path tmp)
            throws Exception {
        seedProject(tmp, "return 42;", "assertEquals(42, new Calc().value());");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);

        // The repository's own src/test/java is visible from the process CWD but
        // must be ignored: only the temp project's tests count.
        TestExecutor.Result result = TestExecutor.execute(
            config(false), tmp.toFile(), classes, tmp.resolve("target/test-classes"));

        assertEquals(1, result.getTotal(), "only the project's own test was found");
    }

    @Test
    void flakyTestIsRetriedAndReportedWhenRetryIsEnabled(@TempDir Path tmp) throws Exception {
        write(tmp, "src/main/java/p/Calc.java",
            "package p; public class Calc { public int value() { return 42; } }");
        // Fails on the first invocation, passes on the second: a flaky test.
        write(tmp, "src/test/java/p/FlakyTest.java",
            "package p;\n"
            + "import org.junit.jupiter.api.Test;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n"
            + "public class FlakyTest {\n"
            + "  static int n = 0;\n"
            + "  @Test void flaky() { n++; assertEquals(2, n); }\n"
            + "}\n");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);

        ProjectConfig cfg = config(false);
        cfg.getTestSettings().setRetryFailed(2);

        TestExecutor.Result result = TestExecutor.execute(
            cfg, tmp.toFile(), classes, tmp.resolve("target/test-classes"));

        assertTrue(result.isSuccess(), "a flaky test passes on retry");
        assertEquals(1, result.getFlaky().size(),
            "the flaky class is reported, never silently hidden (section 21)");
    }

    @Test
    void flakyTestFailsWhenRetryIsDisabled(@TempDir Path tmp) throws Exception {
        write(tmp, "src/main/java/p/Calc.java",
            "package p; public class Calc { public int value() { return 42; } }");
        write(tmp, "src/test/java/p/FlakyTest.java",
            "package p;\n"
            + "import org.junit.jupiter.api.Test;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n"
            + "public class FlakyTest {\n"
            + "  static int n = 0;\n"
            + "  @Test void flaky() { n++; assertEquals(2, n); }\n"
            + "}\n");
        Path classes = tmp.resolve("target/classes");
        compileMain(tmp, classes);

        // Retry is off by default: the failure is final.
        TestExecutor.Result result = TestExecutor.execute(
            config(false), tmp.toFile(), classes, tmp.resolve("target/test-classes"));

        assertFalse(result.isSuccess(), "without retry, a flaky test fails the build");
    }
}
