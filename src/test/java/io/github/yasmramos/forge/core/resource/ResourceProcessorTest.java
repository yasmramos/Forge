package io.github.yasmramos.forge.core.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResourceProcessor} (AGENT.md section 22).
 */
class ResourceProcessorTest {

    @TempDir
    Path tmp;

    private Path classesDir() throws Exception {
        Path dir = tmp.resolve("classes");
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void copiesResourcePreservingLayout() throws Exception {
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources.resolve("META-INF/services"));
        Files.writeString(resources.resolve("application.properties"), "key=value\n");
        Files.writeString(resources.resolve("META-INF/services/com.acme.Spi"), "com.acme.Impl\n");

        ResourceProcessor.Result result = ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), false, null);

        assertEquals(2, result.getCopied());
        assertEquals(0, result.getUnchanged());
        assertEquals("key=value\n",
            Files.readString(classesDir().resolve("application.properties")));
        assertEquals("com.acme.Impl\n",
            Files.readString(classesDir().resolve("META-INF/services/com.acme.Spi")));
    }

    @Test
    void unchangedResourceIsNotRewritten() throws Exception {
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("a.txt"), "same\n");

        ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), false, null);
        ResourceProcessor.Result second = ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), false, null);

        assertEquals(0, second.getCopied(), "an unchanged resource must not be rewritten");
        assertEquals(1, second.getUnchanged());
    }

    @Test
    void filteringIsOptOutByDefault() throws Exception {
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("app.properties"), "version=${project.version}\n");

        ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), false,
            Collections.singletonMap("project.version", "9.9.9"));

        assertEquals("version=${project.version}\n",
            Files.readString(classesDir().resolve("app.properties")),
            "without filtering the placeholder must survive byte-for-byte");
    }

    @Test
    void filteringExpandsDeclaredProperties() throws Exception {
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("app.properties"), "version=${project.version}\n");

        ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), true,
            Collections.singletonMap("project.version", "9.9.9"));

        assertEquals("version=9.9.9\n",
            Files.readString(classesDir().resolve("app.properties")));
    }

    @Test
    void binaryContentSurvivesWhenNotFiltering() throws Exception {
        Path resources = tmp.resolve("resources");
        Files.createDirectories(resources);
        byte[] binary = new byte[] {0, 1, 2, (byte) 0xFF, 10, 13, 0};
        Files.write(resources.resolve("blob.bin"), binary);

        ResourceProcessor.copy(
            Collections.singletonList(resources.toFile()), classesDir(), false, null);

        assertArrayEquals(binary, Files.readAllBytes(classesDir().resolve("blob.bin")));
    }

    @Test
    void missingRootIsSkipped() throws Exception {
        File missing = tmp.resolve("nope").toFile();
        ResourceProcessor.Result result = ResourceProcessor.copy(
            Collections.singletonList(missing), classesDir(), false, null);
        assertEquals(0, result.getTotal());
    }
}
