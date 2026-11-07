package io.github.yasmramos.forge.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cache-key portability and determinism (AGENT.md sections 3 and 17): the key
 * must depend only on content, the project-relative path, and the dependency
 * set, never on where the project lives or on map iteration order.
 */
class ForgeCacheTest {

    private static void writeProject(Path root, String relative, String content) throws Exception {
        Files.createDirectories(root);
        Files.write(root.resolve("forge.toml"), "forge = \"0.1\"".getBytes());
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes());
    }

    @Test
    void keyIsPortableAcrossCheckoutLocations(@TempDir Path tmp) throws Exception {
        // The same project checked out in two different directories (or on two
        // machines) must produce the same key for the same source file.
        Path a = tmp.resolve("checkout-a");
        Path b = tmp.resolve("checkout-b");
        writeProject(a, "src/main/java/p/Z.java", "package p; public class Z { }");
        writeProject(b, "src/main/java/p/Z.java", "package p; public class Z { }");

        File fa = a.resolve("src/main/java/p/Z.java").toFile();
        File fb = b.resolve("src/main/java/p/Z.java").toFile();

        assertEquals(ForgeCache.generateKey(fa, null), ForgeCache.generateKey(fb, null),
            "key must not depend on the absolute checkout location");
    }

    @Test
    void keyIsDeterministicAcrossDependencyOrder(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        writeProject(root, "src/main/java/p/Z.java", "package p; public class Z { }");
        File f = root.resolve("src/main/java/p/Z.java").toFile();

        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("a", "1");
        d1.put("b", "2");
        d1.put("c", "3");
        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("c", "3");
        d2.put("a", "1");
        d2.put("b", "2");

        assertEquals(ForgeCache.generateKey(f, d1), ForgeCache.generateKey(f, d2),
            "key must not depend on dependency map iteration order");
    }

    @Test
    void keyIsScopedToProjectIdentity(@TempDir Path tmp) throws Exception {
        // ~/.forge/cache is shared by every project on the machine, so two
        // unrelated projects with a same-named, same-content file must not
        // collide. The project identity (group:name) provides the separation.
        Path root = tmp.resolve("proj");
        writeProject(root, "src/main/java/p/Z.java", "package p; public class Z { }");
        File f = root.resolve("src/main/java/p/Z.java").toFile();

        String projA = ForgeCache.generateKey(f, null, "com.acme:alpha");
        String projB = ForgeCache.generateKey(f, null, "com.acme:beta");

        assertNotEquals(projA, projB, "different projects must not share a key");
        assertEquals(projA, ForgeCache.generateKey(f, null, "com.acme:alpha"),
            "the same project identity must be stable");
    }

    @Test
    void keyChangesWhenContentChanges(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        writeProject(root, "src/main/java/p/Z.java", "package p; public class Z { int v() { return 1; } }");
        File f = root.resolve("src/main/java/p/Z.java").toFile();
        String before = ForgeCache.generateKey(f, null);

        Files.write(f.toPath(), "package p; public class Z { int v() { return 2; } }".getBytes());

        assertNotEquals(before, ForgeCache.generateKey(f, null),
            "different content must yield a different key");
    }

    @Test
    void keyDistinguishesRelativePathsWithSameContent(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("proj");
        writeProject(root, "src/main/java/p/Z.java", "package p; public class Z { }");
        Files.createDirectories(root.resolve("src/main/java/q"));
        Files.write(root.resolve("src/main/java/q/Z.java"), "package p; public class Z { }".getBytes());

        String keyP = ForgeCache.generateKey(root.resolve("src/main/java/p/Z.java").toFile(), null);
        String keyQ = ForgeCache.generateKey(root.resolve("src/main/java/q/Z.java").toFile(), null);

        assertNotEquals(keyP, keyQ,
            "identical content at different relative paths must not collide");
    }
}
