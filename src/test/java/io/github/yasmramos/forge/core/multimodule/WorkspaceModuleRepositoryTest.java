package io.github.yasmramos.forge.core.multimodule;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the workspace reader that lets modules resolve each other by
 * coordinate (AGENT.md section 5), including the guard that keeps it from
 * shadowing unrelated dependencies.
 */
class WorkspaceModuleRepositoryTest {

    private static File install(Path dir, String artifactId, String version) throws Exception {
        File workspace = dir.toFile();
        Files.write(new File(workspace, artifactId + "-" + version + ".jar").toPath(),
            new byte[]{0});
        Files.write(new File(workspace, artifactId + "-" + version + ".pom").toPath(),
            "<project/>".getBytes(StandardCharsets.UTF_8));
        return workspace;
    }

    @Test
    void servesArtifactsForDeclaredModules(@TempDir Path tmp) throws Exception {
        File workspace = install(tmp, "example.core", "1.0.0");
        WorkspaceModuleRepository repo = new WorkspaceModuleRepository(workspace,
            Collections.singleton("com.example:example.core"));

        assertNotNull(repo.findArtifact(
            new DefaultArtifact("com.example", "example.core", "", "jar", "1.0.0")));
        assertNotNull(repo.findArtifact(
            new DefaultArtifact("com.example", "example.core", "", "pom", "1.0.0")));
    }

    @Test
    void ignoresNonModuleArtifactsEvenIfFileExists(@TempDir Path tmp) throws Exception {
        // A file that happens to share artifactId+version with an external
        // dependency must never be served: the coordinate set does not contain it.
        File workspace = install(tmp, "guava", "32.0.0");
        WorkspaceModuleRepository repo = new WorkspaceModuleRepository(workspace,
            Collections.singleton("com.example:example.core"));

        assertNull(repo.findArtifact(
            new DefaultArtifact("com.google.guava", "guava", "", "jar", "32.0.0")));
    }

    @Test
    void neverServesClassifierArtifacts(@TempDir Path tmp) throws Exception {
        File workspace = install(tmp, "example.core", "1.0.0");
        WorkspaceModuleRepository repo = new WorkspaceModuleRepository(workspace,
            Collections.singleton("com.example:example.core"));

        // findArtifact checks extension, not classifier; a sources jar request
        // must not be answered with the main jar.
        assertNull(repo.findArtifact(
            new DefaultArtifact("com.example", "example.core", "sources", "jar", "1.0.0")));
    }

    @Test
    void missingVersionReturnsNull(@TempDir Path tmp) throws Exception {
        File workspace = install(tmp, "example.core", "1.0.0");
        WorkspaceModuleRepository repo = new WorkspaceModuleRepository(workspace,
            Collections.singleton("com.example:example.core"));

        assertNull(repo.findArtifact(
            new DefaultArtifact("com.example", "example.core", "", "jar", "")));
    }
}
