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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code forge test} (AGENT.md section 10): the command compiles the
 * main sources and reports the test outcome without packaging.
 */
class TestCommandTest {

    private static ProjectConfig config(Path tmp, File root, Path src) throws Exception {
        File toml = new File(root, "forge.toml");
        Files.write(toml.toPath(),
            ("forge = \"0.1\"\n[project]\nname = \"t\"\nversion = \"1.0.0\"\n")
                .getBytes(StandardCharsets.UTF_8));
        ProjectConfig config = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        config.setSourceDirectory(src.toString());
        config.setOutputDirectory(tmp.resolve("classes").toString());
        return config;
    }

    @Test
    void reportsZeroTestsWhenTheProjectHasNone(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.write(src.resolve("A.java"),
            "package p;\npublic class A {}\n".getBytes(StandardCharsets.UTF_8));
        ProjectConfig config = config(tmp, root, src);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = TestCommand.run(new ForgeEngine(config, root), new String[0], out);

        assertEquals(0, code);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("Tests passed."));
    }

    @Test
    void failsNonZeroWhenTheMainSourcesDoNotCompile(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        Path src = tmp.resolve("src/main/java");
        Files.createDirectories(src);
        Files.write(src.resolve("A.java"),
            "package p;\npublic class A { public int f() { return \"x\"; } }\n"
                .getBytes(StandardCharsets.UTF_8));
        ProjectConfig config = config(tmp, root, src);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = TestCommand.run(new ForgeEngine(config, root), new String[0], out);

        assertEquals(1, code, "a compile failure must make forge test exit non-zero");
    }
}
