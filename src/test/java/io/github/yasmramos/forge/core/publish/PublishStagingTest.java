package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.model.ProjectConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for publish staging (AGENT.md section 6): metadata validation and
 * the assembled artifact set, exercised against real files on disk.
 */
class PublishStagingTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static ProjectConfig publishable() {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("io.example");
        config.setName("awesome");
        config.setVersion("1.2.3");
        ProjectConfig.PublishSettings pub = config.getPublishSettings();
        pub.setLicenseName("Apache-2.0");
        pub.setDeveloperNames("Jane Doe");
        pub.setUrl("https://example.io/awesome");
        pub.setScmUrl("scm:git:https://github.com/example/awesome.git");
        return config;
    }

    private static File buildDirWithJar(Path root) throws Exception {
        File outputDir = new File(root.toFile(), "forge-output");
        Files.createDirectories(outputDir.toPath());
        Files.write(new File(outputDir, "forge-main.jar").toPath(), "jar".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(outputDir, "forge-sources.jar").toPath(), "src".getBytes(StandardCharsets.UTF_8));
        return root.toFile();
    }

    @Test
    void stagesAllArtifactsInMavenLayout(@TempDir Path root) throws Exception {
        File buildDir = buildDirWithJar(root);
        File staging = new File(root.toFile(), "forge-publish");

        PublishStaging.Result result = PublishStaging.stage(
            publishable(), buildDir, staging, Collections.emptyList(), T);

        List<String> names = result.getFiles().stream().map(File::getName)
            .collect(java.util.stream.Collectors.toList());
        assertTrue(names.contains("awesome-1.2.3.pom"));
        assertTrue(names.contains("awesome-1.2.3.module"));
        assertTrue(names.contains("awesome-1.2.3.jar"));
        assertTrue(names.contains("awesome-1.2.3-sources.jar"));
        assertTrue(names.contains("awesome-1.2.3-cyclonedx.json"));
        assertEquals(staging.getAbsolutePath(), result.getDirectory().getAbsolutePath());
    }

    @Test
    void warnsWhenSourcesJarIsMissingButKeepsGoing(@TempDir Path root) throws Exception {
        File outputDir = new File(root.toFile(), "forge-output");
        Files.createDirectories(outputDir.toPath());
        Files.write(new File(outputDir, "forge-main.jar").toPath(), "jar".getBytes(StandardCharsets.UTF_8));

        PublishStaging.Result result = PublishStaging.stage(
            publishable(), root.toFile(), new File(root.toFile(), "out"),
            Collections.emptyList(), T);

        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("sources")));
    }

    @Test
    void failsWithPub0001WhenCentralMetadataIsMissing() {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("io.example");
        config.setName("awesome");
        config.setVersion("1.2.3");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> PublishStaging.validate(config));
        assertEquals(ErrorCode.PUB_0001.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("publish.license"));
        assertTrue(ex.getMessage().contains("publish.scm"));
    }

    @Test
    void failsWithPub0002WhenCoordinatesAreMissing() {
        ProjectConfig config = new ProjectConfig();
        config.setName("awesome");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> PublishStaging.validate(config));
        assertEquals(ErrorCode.PUB_0002.getCode(), ex.getCode());
    }

    @Test
    void failsWithPub0004WhenMainJarIsAbsent(@TempDir Path root) throws Exception {
        ForgeException ex = assertThrows(ForgeException.class, () -> PublishStaging.stage(
            publishable(), root.toFile(), new File(root.toFile(), "out"),
            Collections.emptyList(), T));
        assertEquals(ErrorCode.PUB_0004.getCode(), ex.getCode());
    }

    @Test
    void omitsSbomWhenDisabled(@TempDir Path root) throws Exception {
        ProjectConfig config = publishable();
        config.getPublishSettings().setSbom(false);
        File buildDir = buildDirWithJar(root);

        PublishStaging.Result result = PublishStaging.stage(
            config, buildDir, new File(root.toFile(), "out"), Collections.emptyList(), T);

        assertFalse(result.getFiles().stream()
            .anyMatch(f -> f.getName().endsWith("-cyclonedx.json")));
    }
}
