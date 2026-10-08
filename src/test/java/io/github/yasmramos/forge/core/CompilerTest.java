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
}
