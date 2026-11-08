package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code forge publish} staging and repository routing (AGENT.md
 * section 6). Uses {@code --skip-build} so the command exercises staging without
 * recompiling.
 */
class PublishCommandTest {

    private static ProjectConfig publishable(String version) {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("io.example");
        config.setName("awesome");
        config.setVersion(version);
        ProjectConfig.PublishSettings pub = config.getPublishSettings();
        pub.setLicenseName("Apache-2.0");
        pub.setDeveloperNames("Jane Doe");
        pub.setUrl("https://example.io/awesome");
        pub.setScmUrl("scm:git:https://github.com/example/awesome.git");
        return config;
    }

    private static void writeMainJar(File root) throws Exception {
        File outputDir = new File(root, "target/forge-output");
        Files.createDirectories(outputDir.toPath());
        Files.write(new File(outputDir, "forge-main.jar").toPath(),
            "jar".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stagesUnderTargetAndReportsReleaseRepository(@TempDir File root) throws Exception {
        writeMainJar(root);
        ProjectConfig config = publishable("1.2.3");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = PublishCommand.run(new ForgeEngine(config, root),
            new String[] {"--skip-build"}, out);

        assertEquals(0, code);
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(new File(root, "target/forge-publish/awesome-1.2.3.pom").isFile());
        assertTrue(text.contains("Staged"));
        assertTrue(text.contains("Repository target: central"));
    }

    @Test
    void routesSnapshotVersionsToTheSnapshotRepository(@TempDir File root) throws Exception {
        writeMainJar(root);
        ProjectConfig config = publishable("1.2.3-SNAPSHOT");
        config.getPublishSettings().setSnapshotRepository("ossrh-snapshots");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        PublishCommand.run(new ForgeEngine(config, root), new String[] {"--skip-build"}, out);

        assertTrue(buffer.toString(StandardCharsets.UTF_8)
            .contains("Repository target: ossrh-snapshots"));
    }
}
