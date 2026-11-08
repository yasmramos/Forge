package io.github.yasmramos.forge.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the source dependency graph that drives cascading cache
 * invalidation.
 */
class SourceDependenciesTest {

    @Test
    void dependentHashChangesWhenDependencyChanges(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "A.java", "package p; public class A { public static final int MAX = 1; }");
        Path b = write(tmp, "B.java", "package p; public class B { int v() { return A.MAX; } }");

        Map<Path, String> before = SourceDependencies.effectiveHashes(Arrays.asList(a, b));
        write(tmp, "A.java", "package p; public class A { public static final int MAX = 2; }");
        Map<Path, String> after = SourceDependencies.effectiveHashes(Arrays.asList(a, b));

        assertNotEquals(before.get(a), after.get(a), "A's own content changed");
        assertNotEquals(before.get(b), after.get(b),
            "B's hash must change when its dependency A changes, even though B.java is untouched");
    }

    @Test
    void independentFilesKeepStableHashes(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "A.java", "package p; public class A {}");
        Path b = write(tmp, "B.java", "package p; public class B {}");

        Map<Path, String> before = SourceDependencies.effectiveHashes(Arrays.asList(a, b));
        Map<Path, String> after = SourceDependencies.effectiveHashes(Arrays.asList(a, b));

        assertEquals(before.get(a), after.get(a), "hashing must be deterministic");
        assertEquals(before.get(b), after.get(b), "hashing must be deterministic");
    }

    @Test
    void mutuallyReferencingClassesShareOneHash(@TempDir Path tmp) throws Exception {
        Path a = write(tmp, "A.java", "package p; public class A { B b; }");
        Path b = write(tmp, "B.java", "package p; public class B { A a; }");

        Map<Path, String> hashes = SourceDependencies.effectiveHashes(Arrays.asList(a, b));

        assertEquals(hashes.get(a), hashes.get(b),
            "a cycle must collapse to a single hash so it resolves without looping");
    }

    @Test
    void wildcardImportPullsInWholePackage(@TempDir Path tmp) throws Exception {
        Path helper = write(tmp, "util/Helper.java", "package p.util; public class Helper {}");
        Path user = write(tmp, "user/User.java",
            "package p.user; import p.util.*; public class User { Helper h; }");

        Map<Path, String> before = SourceDependencies.effectiveHashes(Arrays.asList(helper, user));
        write(tmp, "util/Helper.java", "package p.util; public class Helper { int x; }");
        Map<Path, String> after = SourceDependencies.effectiveHashes(Arrays.asList(helper, user));

        assertNotEquals(before.get(user), after.get(user),
            "a wildcard-imported type must cascade into the importer's hash");
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes());
        return file;
    }
}
