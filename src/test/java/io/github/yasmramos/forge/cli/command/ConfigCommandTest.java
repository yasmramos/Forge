package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@code forge config} (AGENT.md sections 8, 8bis, 10, 15). */
class ConfigCommandTest {

    private static String run(ProjectConfig config, File root, String variant, String[] args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ConfigCommand.run(config, root, variant, args,
            new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void write(File file, String content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static ProjectConfig load(File root, String variant) throws Exception {
        return ConfigReader.loadConfig(new File(root, "forge.toml"), null, variant);
    }

    @Test
    void printsBaseConfigurationWithDefaults(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"awesome\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n");

        String output = run(load(root, null), root, null, new String[] {});

        assertTrue(output.contains("base"), output);
        assertTrue(output.contains("name     = \"awesome\""), output);
        assertTrue(output.contains("cache scope id    = \"com.example:awesome\""), output);
    }

    @Test
    void showsSelectedVariantAndItsEffect(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"awesome\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n[toolchain]\nlanguage = \"11\"\n"
                + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"17\"\n");

        String output = run(load(root, "prod"), root, "prod", new String[] {});

        assertTrue(output.contains("with variant 'prod'"), output);
        // The overlay actually took effect in the resolved view.
        assertTrue(output.contains("source = 17"), output);
        assertTrue(output.contains("cache scope id    = \"com.example:awesome@prod\""), output);
    }

    @Test
    void listsDeclaredDependenciesSorted(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"awesome\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n[dependencies.compile]\n"
                + "\"org.slf4j:slf4j-api\" = \"2.0.7\"\n\"com.google:guava\" = \"32.0.0\"\n");

        String output = run(load(root, null), root, null, new String[] {});

        int guava = output.indexOf("com.google:guava");
        int slf4j = output.indexOf("org.slf4j:slf4j-api");
        assertTrue(guava >= 0 && slf4j >= 0, output);
        assertTrue(guava < slf4j, "dependencies must be sorted: " + output);
    }

    @Test
    void showResolvedModuleAppliesRootAndModuleLayers(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"multi\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n[project.modules]\n\"example.core\" = \"${project.version}\"\n"
                + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"17\"\n");
        // The module overrides the inherited variant value: its layer must win.
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n"
                + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"21\"\n");

        String output = run(load(root, "prod"), root, "prod",
            new String[] {"--show-resolved", "example.core"});

        assertTrue(output.contains("module 'example.core'"), output);
        assertTrue(output.contains("declared in this module"), output);
        assertTrue(output.contains("source = 21"), output);
    }

    @Test
    void showResolvedOnNonMultiModuleFails(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"awesome\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> run(load(root, null), root, null, new String[] {"--show-resolved", "example.core"}));
        assertEquals("FORGE-CFG-0001", ex.getCode());
    }

    @Test
    void showResolvedUnknownModuleFails(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"multi\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n[project.modules]\n\"example.core\" = \"${project.version}\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> run(load(root, null), root, null, new String[] {"--show-resolved", "example.nope"}));
        assertEquals("FORGE-MMD-0003", ex.getCode());
    }

    @Test
    void showResolvedWithoutModuleNameFails(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"multi\"\nversion = \"1.0.0\"\n"
                + "group = \"com.example\"\n[project.modules]\n\"example.core\" = \"${project.version}\"\n");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> run(load(root, null), root, null, new String[] {"--show-resolved"}));
        assertEquals("FORGE-CFG-0002", ex.getCode());
    }
}
