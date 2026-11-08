package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code forge doctor}'s rendering and exit codes (AGENT.md
 * section 10): 0 when nothing fails, 2 when a check fails (an environment
 * problem per section 10bis).
 */
class DoctorCommandTest {

    @Test
    void exitsZeroAndPrintsChecksForAHealthyProject(@TempDir File root) throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setName("demo");
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        config.setRepositories(repos);
        java.nio.file.Files.write(new File(root, "forge.toml").toPath(),
            "forge = \"0.1\"\n".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = DoctorCommand.run(new ForgeEngine(config, root), false, out);

        String text = buffer.toString(StandardCharsets.UTF_8);
        assertEquals(0, code);
        assertTrue(text.contains("Java runtime"));
        assertTrue(text.contains("forge.toml"));
        // The build runs on a real JDK here, so there is nothing blocking.
        assertTrue(text.contains("All checks passed")
            || text.contains("review the warnings"));
    }

    @Test
    void failsWhenAModuleIsMissingItsForgeToml(@TempDir File root) throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setName("multi");
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("core", "core");
        config.setModules(modules);
        java.nio.file.Files.write(new File(root, "forge.toml").toPath(),
            "forge = \"0.1\"\n".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = DoctorCommand.run(new ForgeEngine(config, root), false, out);

        assertEquals(2, code);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("FAIL"));
    }

    @Test
    void jsonModeEmitsOneObjectPerCheckPlusASummary(@TempDir File root) throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setName("demo");
        java.nio.file.Files.write(new File(root, "forge.toml").toPath(),
            "forge = \"0.1\"\n".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        DoctorCommand.run(new ForgeEngine(config, root), true, out);

        String[] lines = buffer.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertTrue(lines[lines.length - 1].contains("\"summary\""));
        assertTrue(lines[lines.length - 1].contains("\"project\":true"));
        for (String line : lines) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "valid JSON line: " + line);
        }
    }
}
