package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests {@code forge ide} (AGENT.md section 13, phase 1). */
class IdeCommandTest {

    private static ForgeEngine engine(File root) {
        ProjectConfig config = new ProjectConfig();
        config.setName("ide-demo");
        config.setSourceCompatibility("17");
        // Absolute output under the project root: a resolved config stores the
        // output dir this way, and it keeps the generated paths project-relative.
        config.setOutputDirectory(new File(root, "target/classes").getAbsolutePath());
        return new ForgeEngine(config, root);
    }

    private static String run(File root, String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        IdeCommand.run(engine(root), args, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesAllThreeModelsByDefault(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        run(root);

        assertTrue(new File(root, ".idea/modules.xml").isFile());
        assertTrue(new File(root, "ide-demo.iml").isFile());
        assertTrue(new File(root, ".project").isFile());
        assertTrue(new File(root, ".classpath").isFile());
        assertTrue(new File(root, ".settings/org.eclipse.jdt.core.prefs").isFile());
        assertTrue(new File(root, ".vscode/settings.json").isFile());
    }

    @Test
    void narrowsToOneIdeWithTheFlag(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        String output = run(root, "--ide=eclipse");

        assertTrue(new File(root, ".classpath").isFile());
        assertFalse(new File(root, ".idea").exists());
        assertFalse(new File(root, ".vscode").exists());
        assertTrue(output.contains("3 IDE file(s)"));
    }

    @Test
    void dryRunWritesNothing(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        String output = run(root, "--dry-run");

        assertTrue(output.contains("would be written"));
        assertFalse(new File(root, ".idea").exists());
        assertFalse(new File(root, ".classpath").exists());
    }

    @Test
    void rejectsUnknownIde(@TempDir Path tmp) {
        ForgeException ex = assertThrows(ForgeException.class,
            () -> run(tmp.toFile(), "--ide=netbeans"));
        assertEquals("FORGE-IDE-0002", ex.getCode());
    }

    @Test
    void generatedClasspathMatchesDeclaredSourceRoots(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        run(root);
        String classpath = new String(
            Files.readAllBytes(new File(root, ".classpath").toPath()), StandardCharsets.UTF_8);
        assertTrue(classpath.contains("path=\"src/main/java\""));
        assertTrue(classpath.contains("path=\"target/classes\""));
    }
}
