package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for multi-module discovery, inheritance, ordering and cycle detection
 * (AGENT.md section 5). These are pure configuration tests: no network, no
 * compilation, so they run anywhere.
 */
class MultiModuleProjectTest {

    private static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static ProjectConfig rootConfig(File root) throws Exception {
        return ConfigReader.loadConfig(new File(root, "forge.toml"));
    }

    private static void writeRoot(File root, String modulesBlock) throws Exception {
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\n"
                + "name = \"multi\"\n"
                + "version = \"1.0.0\"\n"
                + "group = \"com.example\"\n"
                + modulesBlock
                + "[toolchain]\nlanguage = \"11\"\n"
                + "[repositories]\ncentral = \"https://repo1.maven.org/maven2\"\n");
    }

    @Test
    void discoversModulesInTopologicalOrder(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
            + "\"example.app\" = \"${project.version}\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n[source.main]\njava = [\"src/main/java\"]\n");
        write(new File(root, "app/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
                + "[source.main]\njava = [\"src/main/java\"]\n"
                + "[dependencies.compile]\n\"com.example:example.core\" = \"${project.version}\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        List<ModuleDescriptor> ordered = project.topologicalOrder();

        assertEquals(2, ordered.size());
        assertEquals("example.core", ordered.get(0).getName());
        assertEquals("example.app", ordered.get(1).getName());
        assertEquals("com.example:example.core:1.0.0", ordered.get(0).getCoordinate());
        assertTrue(project.dependenciesOf("example.app").contains("example.core"));
        assertTrue(project.dependenciesOf("example.core").isEmpty());
    }

    @Test
    void moduleInheritsVersionAndGroupFromRoot(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n");
        // The module declares neither version nor group: both must come from the root.
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        ModuleDescriptor core = project.module("example.core");

        assertNotNull(core);
        assertEquals("1.0.0", core.getVersion());
        assertEquals("com.example", core.getGroup());
        assertEquals("com.example:example.core:1.0.0", core.getCoordinate());
    }

    @Test
    void partialOrderIncludesOnlyTargetAndItsDependencies(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"a\" = \"1.0.0\"\n\"b\" = \"1.0.0\"\n\"c\" = \"1.0.0\"\n");
        write(new File(root, "a/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"a\"\n");
        write(new File(root, "b/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"b\"\n"
            + "[dependencies.compile]\n\"com.example:a\" = \"1.0.0\"\n");
        write(new File(root, "c/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"c\"\n"
            + "[dependencies.compile]\n\"com.example:b\" = \"1.0.0\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        List<ModuleDescriptor> partial = project.partialOrder("b");

        assertEquals(2, partial.size(), "b + its dependency a, not c");
        assertEquals("a", partial.get(0).getName());
        assertEquals("b", partial.get(1).getName());
    }

    @Test
    void unknownModuleFailsWithMmd0003(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"a\" = \"1.0.0\"\n");
        write(new File(root, "a/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"a\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        ForgeException ex = assertThrows(ForgeException.class, () -> project.partialOrder("nope"));
        assertEquals("FORGE-MMD-0003", ex.getCode());
    }

    @Test
    void moduleCycleFailsWithMmd0001(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"a\" = \"1.0.0\"\n\"b\" = \"1.0.0\"\n");
        write(new File(root, "a/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"a\"\n"
            + "[dependencies.compile]\n\"com.example:b\" = \"1.0.0\"\n");
        write(new File(root, "b/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"b\"\n"
            + "[dependencies.compile]\n\"com.example:a\" = \"1.0.0\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        ForgeException ex = assertThrows(ForgeException.class, project::topologicalOrder);
        assertEquals("FORGE-MMD-0001", ex.getCode());
        assertTrue(ex.getMessage().contains("a"), ex.getMessage());
    }

    @Test
    void missingModuleTomlFailsWithMmd0002(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n");
        // No core/forge.toml on disk.

        ForgeException ex = assertThrows(ForgeException.class,
            () -> MultiModuleProject.discover(root, rootConfig(root)));
        assertEquals("FORGE-MMD-0002", ex.getCode());
    }

    @Test
    void explicitDirOverrideIsHonored(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = { version = \"1.0.0\", dir = \"modules/core\" }\n");
        write(new File(root, "modules/core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig(root));
        ModuleDescriptor core = project.module("example.core");
        assertNotNull(core);
        assertTrue(core.getDirectory().getPath().endsWith("modules/core"),
            core.getDirectory().getPath());
    }

    // --- Variants in multi-module projects (AGENT.md section 8bis) ---

    @Test
    void moduleVariantOverridesWinOverInheritedRootVariant(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
            + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"17\"\n");
        // The module declares the same variant with a different toolchain: its
        // own overlay must win over the inherited root variant value.
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n"
                + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"21\"\n");

        MultiModuleProject project = MultiModuleProject.discover(
            root, ConfigReader.loadConfig(new File(root, "forge.toml"), null, "prod"), "prod");
        ProjectConfig core = project.module("example.core").getConfig();

        assertEquals("21", core.getSourceCompatibility());
        assertEquals("prod", core.getSelectedVariant());
        assertTrue(project.modulesIgnoringVariant().isEmpty());
    }

    @Test
    void moduleWithoutVariantBuildsBaseAndIsReported(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
            + "\"example.app\" = \"${project.version}\"\n"
            + "[variant.prod]\n[variant.prod.toolchain]\nlanguage = \"17\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        write(new File(root, "app/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
                + "[dependencies.compile]\n\"com.example:example.core\" = \"${project.version}\"\n");

        MultiModuleProject project = MultiModuleProject.discover(
            root, ConfigReader.loadConfig(new File(root, "forge.toml"), null, "prod"), "prod");

        // Neither module declares the variant: both still inherit the root's
        // resolved-with-variant values, but the caller is told they declared
        // nothing of their own, never left in silence (section 8bis).
        assertEquals(java.util.Arrays.asList("example.app", "example.core"),
            project.modulesIgnoringVariant());
    }

    @Test
    void unknownVariantFailsWhenDeclaredNowhere(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        ForgeException ex = assertThrows(ForgeException.class,
            () -> MultiModuleProject.discover(root, rootConfig(root), "benchmark"));
        assertEquals("FORGE-VRT-0001", ex.getCode());
    }

    @Test
    void variantDeclaredOnlyInAModuleIsAccepted(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n");
        // A module-specific variant (e.g. benchmark on one hot module) is
        // legitimate even when the root knows nothing about it (section 8bis).
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n"
                + "[variant.benchmark]\n[variant.benchmark.toolchain]\nlanguage = \"17\"\n");

        MultiModuleProject project = MultiModuleProject.discover(
            root, ConfigReader.loadConfig(new File(root, "forge.toml"), null, "benchmark"),
            "benchmark");

        assertEquals("17", project.module("example.core").getConfig().getSourceCompatibility());
        assertTrue(project.modulesIgnoringVariant().isEmpty());
    }
}
