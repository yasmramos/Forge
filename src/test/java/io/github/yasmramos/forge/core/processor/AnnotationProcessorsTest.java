package io.github.yasmramos.forge.core.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for annotation-processor discovery (AGENT.md section 23).
 */
class AnnotationProcessorsTest {

    @TempDir
    Path tmp;

    @Test
    void discoversProcessorsFromDirectory() throws Exception {
        Path root = tmp.resolve("lib");
        Path service = root.resolve(AnnotationProcessors.SERVICE_FILE);
        Files.createDirectories(service.getParent());
        Files.writeString(service, "# comment\nproc.One\n\nproc.Two\n");

        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(Collections.singletonList(root.toFile()));

        assertEquals(List.of("proc.One", "proc.Two"), discovery.getNames());
        assertEquals(Collections.singletonList(root.toFile()), discovery.getProcessorPath());
        assertFalse(discovery.isEmpty());
    }

    @Test
    void discoversProcessorsFromJar() throws Exception {
        Path jar = tmp.resolve("lombok.jar");
        writeJar(jar, AnnotationProcessors.SERVICE_FILE, "proc.FromJar\n");

        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(Collections.singletonList(jar.toFile()));

        assertEquals(List.of("proc.FromJar"), discovery.getNames());
        assertEquals(Collections.singletonList(jar.toFile()), discovery.getProcessorPath());
    }

    @Test
    void ignoresEntriesWithoutServiceFile() throws Exception {
        Path jar = tmp.resolve("plain.jar");
        writeJar(jar, "com/acme/Thing.class", "not a real class");

        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(Collections.singletonList(jar.toFile()));

        assertTrue(discovery.isEmpty());
        assertTrue(discovery.getProcessorPath().isEmpty());
    }

    @Test
    void missingEntryIsSkippedNotFatal() {
        File missing = tmp.resolve("nope.jar").toFile();
        AnnotationProcessors.Discovery discovery =
            AnnotationProcessors.discover(Collections.singletonList(missing));
        assertTrue(discovery.isEmpty());
    }

    private static void writeJar(Path jar, String entryName, String content) throws Exception {
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
