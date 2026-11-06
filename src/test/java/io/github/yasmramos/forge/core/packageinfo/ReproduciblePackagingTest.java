package io.github.yasmramos.forge.core.packageinfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reproducibility guarantees of packaging (AGENT.md decision 17):
 * identical inputs produce byte-identical JARs, entry order is deterministic,
 * and the epoch resolution order is fixed.
 */
class ReproduciblePackagingTest {

    @Test
    void identicalInputsProduceByteIdenticalJars(@TempDir File dir) throws Exception {
        Path classes = dir.toPath().resolve("classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.write(classes.resolve("com/example/A.class"),
            "aaa".getBytes(StandardCharsets.UTF_8));
        Files.write(classes.resolve("com/example/B.class"),
            "bbbb".getBytes(StandardCharsets.UTF_8));
        // A file with an obviously different mtime must not leak into the JAR.
        Files.setLastModifiedTime(classes.resolve("com/example/A.class"),
            java.nio.file.attribute.FileTime.fromMillis(0));

        Path jar1 = dir.toPath().resolve("out1/forge-main.jar");
        Path jar2 = dir.toPath().resolve("out2/forge-main.jar");
        long epoch = ReproducibleBuild.FALLBACK_EPOCH_SECONDS;

        JarWriter.writeDirectory(classes, jar1, epoch);
        // Change mtimes between the two runs: bytes must not change.
        Files.setLastModifiedTime(classes.resolve("com/example/B.class"),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        JarWriter.writeDirectory(classes, jar2, epoch);

        assertArrayEquals(Files.readAllBytes(jar1), Files.readAllBytes(jar2),
            "same inputs + same epoch must yield byte-identical JARs");
    }

    @Test
    void entriesAreSortedAndTimestampIsFixed(@TempDir File dir) throws Exception {
        Path classes = dir.toPath().resolve("classes");
        Files.createDirectories(classes);
        Files.write(classes.resolve("z.txt"), "z".getBytes(StandardCharsets.UTF_8));
        Files.write(classes.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));

        Path jar = dir.toPath().resolve("forge-main.jar");
        long epoch = 1_000_000_000L;
        JarWriter.writeDirectory(classes, jar, epoch);

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var names = zip.stream().map(ZipEntry::getName).toList();
            assertEquals("META-INF/MANIFEST.MF", names.get(0), "manifest is written first");
            assertEquals("a.txt", names.get(1));
            assertEquals("z.txt", names.get(2));
            for (var e : zip.stream().toList()) {
                assertEquals(epoch * 1000L, e.getTime(),
                    "every entry carries the fixed build timestamp");
            }
        }
    }

    @Test
    void manifestIsPresentAndValid(@TempDir File dir) throws Exception {
        Path classes = dir.toPath().resolve("classes");
        Files.createDirectories(classes);
        Path jar = dir.toPath().resolve("forge-main.jar");
        JarWriter.writeDirectory(classes, jar, ReproducibleBuild.FALLBACK_EPOCH_SECONDS);

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            assertTrue(manifest != null, "manifest entry exists");
            String content = new String(zip.getInputStream(manifest).readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(content.startsWith("Manifest-Version: 1.0"), content);
            assertTrue(content.endsWith("\r\n\r\n"), "manifest ends with a blank line");
        }
    }

    @Test
    void epochResolutionHonorsExplicitValue(@TempDir File dir) {
        // Explicit epoch seconds win over everything else.
        assertEquals(1_700_000_000L,
            ReproducibleBuild.epochSeconds(dir, "1700000000"));
        // ISO-8601 instants are accepted too.
        assertEquals(1_700_000_000L,
            ReproducibleBuild.epochSeconds(dir, "2023-11-14T22:13:20Z"));
    }

    @Test
    void epochResolutionFallsBackWithoutGitOrConfig(@TempDir File dir) {
        // No .git in a temp dir and no configured value: ZIP epoch minimum.
        assertEquals(ReproducibleBuild.FALLBACK_EPOCH_SECONDS,
            ReproducibleBuild.epochSeconds(dir, null));
    }

    @Test
    void differentEpochProducesDifferentBytes(@TempDir File dir) throws Exception {
        Path classes = dir.toPath().resolve("classes");
        Files.createDirectories(classes);
        Files.write(classes.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));

        Path jar1 = dir.toPath().resolve("j1.jar");
        Path jar2 = dir.toPath().resolve("j2.jar");
        JarWriter.writeDirectory(classes, jar1, 1_000_000_000L);
        // ZIP stores DOS timestamps at 2-second resolution, so pick an epoch
        // far enough apart to be observable.
        JarWriter.writeDirectory(classes, jar2, 1_700_000_000L);

        assertFalse(java.util.Arrays.equals(Files.readAllBytes(jar1), Files.readAllBytes(jar2)),
            "the timestamp is part of the artifact bytes");
    }
}
