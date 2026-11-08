package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.cache.ForgeCache;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.model.DependencyResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Compiler class.
 * Tests basic instantiation and real batch compilation behavior.
 */
class CompilerTest {

    @Test
    void testCompilerInstantiation() {
        Compiler compiler = new Compiler();
        assertNotNull(compiler, "Compiler should be instantiable");
    }

    @Test
    void testCompilerHasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            new Compiler();
        }, "Compiler should have a working default constructor");
    }

    @Test
    void testInProcessCompilerIsUsedWhenAvailable() {
        Compiler compiler = new Compiler();
        // The build runs on a JDK, so the JSR-199 system compiler must be present
        assertTrue(compiler.isInProcessCompilationAvailable(),
                "JSR-199 in-process compiler should be available on a JDK");
    }

    @Test
    void testCompileEmptyBatchSucceeds() {
        Compiler compiler = new Compiler();

        CompilationResult empty = compiler.compileBatch(Collections.emptyList(), null, null, "target/test-classes");
        CompilationResult nullList = compiler.compileBatch(null, null, null, "target/test-classes");

        assertTrue(empty.isSuccess(), "Empty batch should report success");
        assertTrue(nullList.isSuccess(), "Null batch should report success");
    }

    @Test
    void testCompileBatchProducesClassFiles(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");

        // Two interdependent sources: B references A, which only resolves
        // when both files are compiled in a single batch invocation.
        Files.write(src.resolve("A.java"),
            "public class A { public int value() { return 42; } }".getBytes());
        Files.write(src.resolve("B.java"),
            "public class B { public int doubled() { return new A().value() * 2; } }".getBytes());

        Compiler compiler = new Compiler();
        CompilationResult result = compiler.compileBatch(
            Arrays.asList(src.resolve("A.java"), src.resolve("B.java")), null, null, out.toString());

        assertTrue(result.isSuccess(), "Batch compilation should succeed");
        assertTrue(Files.exists(out.resolve("A.class")), "A.class should be emitted");
        assertTrue(Files.exists(out.resolve("B.class")), "B.class should be emitted");
    }

    @Test
    void testCompileBatchReportsFailureOnSyntaxError(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");

        Files.write(src.resolve("Broken.java"),
            "public class Broken { this is not valid java".getBytes());

        Compiler compiler = new Compiler();
        CompilationResult result = compiler.compileBatch(
            Collections.singletonList(src.resolve("Broken.java")), null, null, out.toString());

        assertFalse(result.isSuccess(), "Invalid source should fail compilation");
    }

    @Test
    void testIncrementalSecondRunUsesCache(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Files.write(src.resolve("C.java"), "public class C {}".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();

        CompilationResult first = compiler.compileBatch(
            Collections.singletonList(src.resolve("C.java")), null, cache, out.toString());
        assertTrue(first.isSuccess(), "First compilation should succeed");
        assertTrue(Files.exists(out.resolve("C.class")));

        // Second run with unchanged sources must hit the cache (no javac spawn).
        CompilationResult second = compiler.compileBatch(
            Collections.singletonList(src.resolve("C.java")), null, cache, out.toString());
        assertTrue(second.isSuccess(), "Cached run should report success");
        assertEquals(1, second.getCachedFiles(), "Second run should report a cache hit");
    }

    @Test
    void cacheInvalidatesOnContentChangeNotTimestamp(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path f = src.resolve("E.java");
        Files.write(f, "public class E { public int v() { return 1; } }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(
            Collections.singletonList(f), null, cache, out.toString()).isSuccess());

        // Rewrite the bytes but keep the previous mtime: a timestamp-based cache
        // would wrongly serve the stale class, while a content-based one must
        // recompile (AGENT.md section 3: content, never time).
        long mtime = Files.getLastModifiedTime(f).toMillis();
        Files.write(f, "public class E { public int v() { return 2; } }".getBytes());
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(mtime));

        CompilationResult second = compiler.compileBatch(
            Collections.singletonList(f), null, cache, out.toString());
        assertTrue(second.isSuccess());
        assertEquals(0, second.getCachedFiles(),
            "changed bytes must invalidate the cache even when the timestamp is unchanged");
    }

    @Test
    void dependencyChangeInvalidatesDependentCacheEntry(@TempDir Path tmp) throws Exception {
        // Regression: javac inlines static final constants, so a change in A can
        // change B.class even when B.java is untouched. Keying B's entry on its
        // own bytes alone served the stale B.class; the effective hash must fold
        // in the dependency so both are recompiled (AGENT.md sections 3 and 17).
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        Files.write(a, "package p; public class A { public static final int MAX = 10; }".getBytes());
        Files.write(b, ("package p; public class B {"
            + " public static int value() { return A.MAX; } }").getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString()).isSuccess());
        assertEquals(10, invokeValue(out, "p.B"));

        // Only A.java changes.
        Files.write(a, "package p; public class A { public static final int MAX = 99; }".getBytes());
        CompilationResult second = compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString());

        assertTrue(second.isSuccess());
        assertEquals(0, second.getCachedFiles(),
            "the dependent file must be recompiled when its dependency changes");
        assertEquals(99, invokeValue(out, "p.B"),
            "B.class must reflect the new inlined constant");
    }

    @Test
    void toggledConstantRestoresCorrectOutputFromCache(@TempDir Path tmp) throws Exception {
        // Regression: the cache must store and restore outputs, not just check
        // that a file with the right name exists. After A goes 10 -> 99 -> 10,
        // A's key is the one from the first build again; a presence-only hit
        // would leave the 99 bytes on disk.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path a = src.resolve("A.java");
        Files.write(a, "package p; public class A { public static final int MAX = 10; }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(Collections.singletonList(a), null, cache, out.toString()).isSuccess());
        assertEquals(10, constantOf(out, "p.A"));

        Files.write(a, "package p; public class A { public static final int MAX = 99; }".getBytes());
        assertTrue(compiler.compileBatch(Collections.singletonList(a), null, cache, out.toString()).isSuccess());
        assertEquals(99, constantOf(out, "p.A"));

        Files.write(a, "package p; public class A { public static final int MAX = 10; }".getBytes());
        CompilationResult third = compiler.compileBatch(Collections.singletonList(a), null, cache, out.toString());

        assertTrue(third.isSuccess());
        assertEquals(10, constantOf(out, "p.A"),
            "the cached output must be restored, not the stale class left on disk");
    }

    /** Reads {@code MAX} from the compiled class, exercising the real bytes on disk. */
    private static int constantOf(Path classesDir, String fqn) throws Exception {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] { classesDir.toUri().toURL() }, null)) {
            return Class.forName(fqn, true, loader).getField("MAX").getInt(null);
        }
    }

    /** Invokes the compiled {@code value()} method, which returns an inlined constant. */
    private static int invokeValue(Path classesDir, String fqn) throws Exception {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] { classesDir.toUri().toURL() }, null)) {
            Class<?> type = Class.forName(fqn, true, loader);
            return (Integer) type.getMethod("value").invoke(null);
        }
    }

    @Test
    void lostNestedClassIsRestoredFromWarmCache(@TempDir Path tmp) throws Exception {
        // Regression: a hit must account for nested/anonymous classes, not just
        // the top-level name. Losing Outer$Inner.class while Outer.class survives
        // must not yield an incomplete build; the stored output is restored
        // (AGENT.md section 3: outputs are stored and put back in position).
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path outer = src.resolve("Outer.java");
        Files.write(outer, ("public class Outer {"
            + " static class Inner {}"
            + " public int v() { return new Inner().hashCode(); } }").getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(Arrays.asList(outer), null, cache, out.toString()).isSuccess());
        Path innerClass = out.resolve("Outer$Inner.class");
        assertTrue(Files.isRegularFile(innerClass), "nested class must be compiled");

        Files.delete(innerClass);

        CompilationResult second = compiler.compileBatch(Arrays.asList(outer), null, cache, out.toString());
        assertTrue(second.isSuccess());
        assertEquals(1, second.getCachedFiles(),
            "the entry is a hit; its output is restored from cache");
        assertTrue(Files.isRegularFile(innerClass),
            "the nested class must be back in place");
    }

    @Test
    void incrementalRecompileResolvesUnchangedSiblingClasses(@TempDir Path tmp) throws Exception {
        // Regression: only stale sources are handed to javac, so a stale file
        // that references an unchanged sibling needs that sibling's already
        // compiled class on the classpath. Without the output directory on the
        // classpath this failed with "cannot find symbol" on the very first
        // incremental rebuild - the common edit-one-file workflow.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        Files.write(a, "public class A { public int v() { return 1; } }".getBytes());
        Files.write(b, "public class B { public int v() { return new A().v() + 1; } }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString()).isSuccess());

        Files.write(b, "public class B { public int v() { return new A().v() + 2; } }".getBytes());

        CompilationResult second = compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString());
        assertTrue(second.isSuccess(),
            "editing B must not fail resolving the unchanged sibling A");
        assertEquals(1, second.getCachedFiles(), "A is unchanged and must stay cached");
    }

    @Test
    void warmCacheWithSameContentNewTimestampStillHits(@TempDir Path tmp) throws Exception {
        // A re-checkout or `rsync -a` keeps the bytes but moves the mtime. The
        // cache key hashes content, so this must stay a hit: a timestamp check
        // in isValid would make it a spurious miss (AGENT.md section 3).
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path f = src.resolve("F.java");
        Files.write(f, "public class F { public int v() { return 1; } }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(
            Collections.singletonList(f), null, cache, out.toString()).isSuccess());

        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
            System.currentTimeMillis() + 60_000));

        CompilationResult second = compiler.compileBatch(
            Collections.singletonList(f), null, cache, out.toString());
        assertTrue(second.isSuccess());
        assertEquals(1, second.getCachedFiles(),
            "same bytes with a new mtime must still be a content cache hit");
    }

    @Test
    void warmCacheMissingOneClassIsRestored(@TempDir Path tmp) throws Exception {
        // Regression: the warm-cache guard accepted the cache as long as *any*
        // .class existed, so a partial output (crash, partial remote-cache
        // restore) produced an incomplete build that still reported success.
        // Now the missing class is restored from the entry's stored outputs.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Files.write(src.resolve("A.java"), "public class A { public int v() { return 1; } }".getBytes());
        Files.write(src.resolve("B.java"), "public class B { public int v() { return 2; } }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        List<Path> sources = Arrays.asList(src.resolve("A.java"), src.resolve("B.java"));
        assertTrue(compiler.compileBatch(sources, null, cache, out.toString()).isSuccess());
        assertTrue(Files.exists(out.resolve("B.class")));

        // Only one class disappears; A.class remains, so a warm-cache check
        // that only looks for *any* .class would wrongly trust the cache.
        Files.delete(out.resolve("B.class"));

        CompilationResult second = compiler.compileBatch(sources, null, cache, out.toString());
        assertTrue(second.isSuccess());
        assertEquals(2, second.getCachedFiles(),
            "both files are cache hits; the lost class is restored, not recompiled");
        assertTrue(Files.exists(out.resolve("B.class")),
            "the missing class must be back in place");
    }

    @Test
    void changedFileMissesAndLostOutputIsRestored(@TempDir Path tmp) throws Exception {
        // Mixed case: A changed on disk (content key misses) and B's class file
        // was deleted. A must recompile; B's stored output is restored.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        Files.write(a, "public class A { public int v() { return 1; } }".getBytes());
        Files.write(b, "public class B { public int v() { return 2; } }".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();
        assertTrue(compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString()).isSuccess());

        // Change A (content miss), delete B.class (output miss).
        Files.write(a, "public class A { public int v() { return 10; } }".getBytes());
        Files.delete(out.resolve("B.class"));

        CompilationResult second = compiler.compileBatch(Arrays.asList(a, b), null, cache, out.toString());
        assertTrue(second.isSuccess());
        assertEquals(1, second.getCachedFiles(),
            "A changed (recompiled); B is a hit whose output is restored");
        assertTrue(Files.exists(out.resolve("A.class")));
        assertTrue(Files.exists(out.resolve("B.class")), "the deleted class must come back");
    }

    @Test
    void warmCacheWithoutClassesRestoresOutputs(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path out = tmp.resolve("out");
        Files.write(src.resolve("D.java"), "public class D {}".getBytes());

        ForgeCache cache = new ForgeCache(tmp.resolve("cache").toString());
        Compiler compiler = new Compiler();

        assertTrue(compiler.compileBatch(
            Collections.singletonList(src.resolve("D.java")), null, cache, out.toString())
            .isSuccess());
        assertTrue(Files.exists(out.resolve("D.class")));

        // Simulate 'forge clean': the classes directory is gone but the
        // persistent cache survives. The build must regenerate the classes
        // instead of trusting the warm cache and emitting nothing.
        deleteRecursively(out);

        CompilationResult afterClean = compiler.compileBatch(
            Collections.singletonList(src.resolve("D.java")), null, cache, out.toString());
        assertTrue(afterClean.isSuccess(), "Build after clean must succeed");
        assertEquals(1, afterClean.getCachedFiles(),
            "the entry is a hit; its stored output is restored");
        assertTrue(Files.exists(out.resolve("D.class")),
            "Classes must be back in place after a clean");
    }

    // --- Toolchain language level and [compile].args (sections 20, 10) ---

    @Test
    void toolchainLanguageDrivesSourceAndTarget(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.write(src.resolve("T.java"), "public class T {}".getBytes());

        // Explicit level: bytecode must be emitted for that release.
        Path out8 = tmp.resolve("out8");
        Compiler compiler = new Compiler();
        compiler.setToolchain("8", "8");
        assertTrue(compiler.compileBatch(
            Collections.singletonList(src.resolve("T.java")), null, null, out8.toString()).isSuccess());
        assertEquals(52, classMajorVersion(out8.resolve("T.class")),
            "-source/-target 8 must produce Java 8 bytecode (major 52)");

        // No declared toolchain: javac's own default (the running JDK) applies,
        // so projects that never set [toolchain] are unaffected.
        Path outDefault = tmp.resolve("out-default");
        new Compiler().compileBatch(
            Collections.singletonList(src.resolve("T.java")), null, null, outDefault.toString());
        assertEquals(44 + Runtime.version().feature(), classMajorVersion(outDefault.resolve("T.class")),
            "an undeclared toolchain must keep javac's default level");
    }

    @Test
    void compilerArgsReachJavac(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        // Raw ArrayList assigned to a parameterized type: a mandatory
        // "unchecked" warning, valid Java.
        Files.write(src.resolve("Warn.java"),
            ("import java.util.ArrayList;\nimport java.util.List;\n"
                + "public class Warn { List<String> l = new ArrayList(); }").getBytes());

        // Control: the warning does not fail a normal compile.
        Path outOk = tmp.resolve("out-ok");
        assertTrue(new Compiler().compileBatch(
            Collections.singletonList(src.resolve("Warn.java")), null, null, outOk.toString()).isSuccess(),
            "a warning alone must not fail the build");

        // With -Xlint:unchecked -Werror the very same source must fail, proving
        // both arguments reached javac (and are appended last).
        Path outErr = tmp.resolve("out-err");
        Compiler strict = new Compiler();
        strict.setCompilerArgs(Arrays.asList("-Xlint:unchecked", "-Werror"));
        assertFalse(strict.compileBatch(
            Collections.singletonList(src.resolve("Warn.java")), null, null, outErr.toString()).isSuccess(),
            "-Xlint:unchecked -Werror must turn the warning into a failure");
    }

    /** Class file major version, from bytes 6-7 of the header. */
    private static int classMajorVersion(Path classFile) throws Exception {
        byte[] header = Files.readAllBytes(classFile);
        return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (Exception ignored) { }
            });
        }
    }
}
