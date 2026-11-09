package io.github.yasmramos.forge.core.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for selective test execution (AGENT.md section 21): the choice of
 * which tests a change can affect, and the portable state it is based on.
 */
class SelectiveTestsTest {

    private static Path write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes());
        return file;
    }

    private static List<Path> testSources(Path root) {
        return Arrays.asList(
            root.resolve("src/test/java/p/ATest.java"),
            root.resolve("src/test/java/p/BTest.java"));
    }

    @Test
    void firstRunWithoutStateRunsEverything(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        write(tmp, "src/test/java/p/BTest.java",
            "package p; public class BTest {}");

        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(),
            Collections.singletonList(main), testSources(tmp), Collections.emptyList());

        assertTrue(s.isBaseline(), "no state file means no baseline");
        assertTrue(s.isAll(), "a first run must run every test");
        assertEquals(2, s.getAffected().size());
    }

    @Test
    void onlyTestsTouchingTheChangedMainClassRerun(@TempDir Path tmp) throws Exception {
        Path mainA = write(tmp, "src/main/java/p/A.java", "package p; public class A { int x; }");
        Path mainB = write(tmp, "src/main/java/p/B.java", "package p; public class B { int y; }");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        Path bTest = write(tmp, "src/test/java/p/BTest.java",
            "package p; public class BTest { B b; }");

        List<Path> mains = Arrays.asList(mainA, mainB);
        List<Path> tests = Arrays.asList(aTest, bTest);

        // Establish a baseline with a fully green run.
        SelectiveTests.record(tmp.toFile(), mains, tests, Collections.emptyList());

        // Change only A. ATest depends on A, BTest does not.
        write(tmp, "src/main/java/p/A.java", "package p; public class A { int x; int z; }");

        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(), mains, tests,
            Collections.emptyList());

        assertFalse(s.isAll(), "a baseline exists, so selection applies");
        assertEquals(1, s.getAffected().size(), "only the affected test re-runs");
        assertEquals(aTest, s.getAffected().get(0));
        assertEquals(1, s.getSkipped());
    }

    @Test
    void noChangeSelectsNothing(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        List<Path> mains = Collections.singletonList(main);
        List<Path> tests = Collections.singletonList(aTest);

        SelectiveTests.record(tmp.toFile(), mains, tests, Collections.emptyList());
        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(), mains, tests,
            Collections.emptyList());

        assertTrue(s.getAffected().isEmpty(), "nothing changed, nothing to run");
        assertEquals(1, s.getSkipped());
    }

    @Test
    void changingATestSourceRerunsThatTest(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        Path bTest = write(tmp, "src/test/java/p/BTest.java",
            "package p; public class BTest {}");
        List<Path> mains = Collections.singletonList(main);
        List<Path> tests = Arrays.asList(aTest, bTest);

        SelectiveTests.record(tmp.toFile(), mains, tests, Collections.emptyList());
        write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; int extra; }");

        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(), mains, tests,
            Collections.emptyList());

        assertEquals(1, s.getAffected().size());
        assertEquals(aTest, s.getAffected().get(0));
    }

    @Test
    void aResourceChangeRerunsEverything(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        Path resources = tmp.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.write(resources.resolve("config.txt"), "v1".getBytes());
        List<Path> mains = Collections.singletonList(main);
        List<Path> tests = Collections.singletonList(aTest);

        SelectiveTests.record(tmp.toFile(), mains, tests, Collections.singletonList(resources));
        Files.write(resources.resolve("config.txt"), "v2".getBytes());

        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(), mains, tests,
            Collections.singletonList(resources));

        assertTrue(s.isAll(), "a resource change must re-run every test");
    }

    @Test
    void stateFileIsPortableRelativeToml(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");

        SelectiveTests.record(tmp.toFile(), Collections.singletonList(main),
            Collections.singletonList(aTest), Collections.emptyList());

        Path state = tmp.resolve(".forge/test-state.toml");
        assertTrue(Files.isRegularFile(state), "state is written under .forge/");
        String body = new String(Files.readAllBytes(state));
        assertTrue(body.contains("schema = \"1\""), "schema is recorded");
        assertTrue(body.contains("src/test/java/p/ATest.java"),
            "paths are stored relative to the project, never absolute");
        assertFalse(body.contains(tmp.toString()),
            "an absolute path would break portability across checkouts (section 17)");
    }

    @Test
    void corruptStateFallsBackToRunningEverything(@TempDir Path tmp) throws Exception {
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");
        Files.createDirectories(tmp.resolve(".forge"));
        Files.write(tmp.resolve(".forge/test-state.toml"), "this is not = valid toml {{{".getBytes());

        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(),
            Collections.singletonList(main), Collections.singletonList(aTest),
            Collections.emptyList());

        assertTrue(s.isAll(), "an unreadable state must never skip a test");
    }

    @Test
    void resourceHashIsStableAndContentBased(@TempDir Path tmp) throws Exception {
        Path resources = tmp.resolve("res");
        Files.createDirectories(resources);
        Files.write(resources.resolve("a.txt"), "one".getBytes());

        String first = SelectiveTests.resourceHash(Collections.singletonList(resources));
        String second = SelectiveTests.resourceHash(Collections.singletonList(resources));
        assertEquals(first, second, "hashing must be deterministic");

        Files.write(resources.resolve("a.txt"), "two".getBytes());
        String changed = SelectiveTests.resourceHash(Collections.singletonList(resources));
        assertFalse(first.equals(changed), "content change must change the hash");
    }

    @Test
    void noTestSourcesSelectsNothing(@TempDir Path tmp) throws Exception {
        SelectiveTests.Selection s = SelectiveTests.select(tmp.toFile(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertTrue(s.getAffected().isEmpty());
        assertEquals(0, s.getTotal());
    }

    @Test
    void projectRootArgumentIsHonoredNotTheWorkingDirectory(@TempDir Path tmp) throws Exception {
        // The JVM working directory is the repository, not the temp project; the
        // state must land under the project passed in, never under the CWD.
        File root = tmp.toFile();
        Path main = write(tmp, "src/main/java/p/A.java", "package p; public class A {}");
        Path aTest = write(tmp, "src/test/java/p/ATest.java",
            "package p; public class ATest { A a; }");

        SelectiveTests.record(root, Collections.singletonList(main),
            Collections.singletonList(aTest), Collections.emptyList());

        assertTrue(Files.isRegularFile(tmp.resolve(".forge/test-state.toml")));
        assertFalse(Files.exists(new File(".forge/test-state.toml").toPath().toAbsolutePath()
            .normalize()), "state must never be written to the process CWD");
    }
}
