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
    void warmCacheMissingOneClassRecompiles(@TempDir Path tmp) throws Exception {
        // Regression: the warm-cache guard accepted the cache as long as *any*
        // .class existed, so a partial output (crash, partial remote-cache
        // restore) produced an incomplete build that still reported success.
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
        assertEquals(1, second.getCachedFiles(),
            "the unchanged file stays cached; only the lost class is recompiled");
        assertTrue(Files.exists(out.resolve("B.class")),
            "the missing class must be regenerated");
    }

    @Test
    void changedFileWithPresentOutputIsAHitButLostOutputIsNot(@TempDir Path tmp) throws Exception {
        // Mixed case: A changed on disk (content key misses) and B's class file
        // was deleted (output misses). Both must recompile; a file whose output
        // vanished is stale even when its bytes are unchanged.
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
        assertEquals(0, second.getCachedFiles(),
            "both files must be recompiled: one changed, the other lost its output");
        assertTrue(Files.exists(out.resolve("A.class")));
        assertTrue(Files.exists(out.resolve("B.class")), "the deleted class must come back");
    }

    @Test
    void warmCacheWithoutClassesRecompiles(@TempDir Path tmp) throws Exception {
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
        assertEquals(0, afterClean.getCachedFiles(),
            "Warm cache must be distrusted when its outputs are missing");
        assertTrue(Files.exists(out.resolve("D.class")),
            "Classes must be regenerated after a clean");
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
