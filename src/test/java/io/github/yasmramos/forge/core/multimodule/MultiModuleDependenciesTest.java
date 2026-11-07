package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the aggregate dependency view of a multi-module project (AGENT.md
 * sections 5, 7bis and 8bis): the union of module compile dependencies, the
 * combined config hash, and the single root lock. All offline.
 */
class MultiModuleDependenciesTest {

    private static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRoot(File root, String modulesBlock) throws Exception {
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\nname = \"multi\"\nversion = \"1.0.0\"\ngroup = \"com.example\"\n"
                + modulesBlock
                + "[toolchain]\nlanguage = \"11\"\n"
                + "[repositories]\ncentral = \"https://repo1.maven.org/maven2\"\n");
    }

    private static MultiModuleProject discover(File root) throws Exception {
        ProjectConfig config = ConfigReader.loadConfig(new File(root, "forge.toml"));
        return MultiModuleProject.discover(root, config);
    }

    @Test
    void unionOfModuleCompileDependenciesExcludesInterModuleCoordinates(@TempDir Path tmp)
            throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
            + "\"example.app\" = \"${project.version}\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n"
                + "[dependencies.compile]\n\"org.slf4j:slf4j-api\" = \"2.0.7\"\n");
        write(new File(root, "app/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
                + "[dependencies.compile]\n"
                + "\"com.example:example.core\" = \"${project.version}\"\n"
                + "\"com.google.guava:guava\" = \"32.0.0-jre\"\n");

        List<String> roots = MultiModuleDependencies.compileRoots(discover(root));

        // The inter-module coordinate is dropped: core is built locally, not locked.
        assertTrue(roots.contains("org.slf4j:slf4j-api:2.0.7"), roots.toString());
        assertTrue(roots.contains("com.google.guava:guava:32.0.0-jre"), roots.toString());
        assertFalse(roots.contains("com.example:example.core:1.0.0"), roots.toString());
        // Deterministic, sorted order (so the resolved graph and lock are stable).
        assertEquals(roots.stream().sorted().collect(java.util.stream.Collectors.toList()), roots);
    }

    @Test
    void interModuleOnlyProjectHasNoExternalRoots(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n\"example.app\" = \"1.0.0\"\n");
        write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        write(new File(root, "app/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
            + "[dependencies.compile]\n\"com.example:example.core\" = \"1.0.0\"\n");

        assertTrue(MultiModuleDependencies.compileRoots(discover(root)).isEmpty());
    }

    @Test
    void combinedConfigHashCoversRootAndEveryModule(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n\"example.app\" = \"1.0.0\"\n");
        write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        write(new File(root, "app/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.app\"\n");

        MultiModuleProject project = discover(root);
        String before = Lockfile.hashOf(MultiModuleDependencies.namedConfigs(root, project));

        // Changing a module's config must change the combined hash: the lock is
        // only valid for the exact set of configs that produced it (section 8bis).
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n[project.properties]\n\"k\" = \"v\"\n");
        MultiModuleProject changed = discover(root);
        String after = Lockfile.hashOf(MultiModuleDependencies.namedConfigs(root, changed));

        assertFalse(before.equals(after), "a module config change must invalidate the lock hash");
    }

    @Test
    void combinedConfigHashIsPathIndependent(@TempDir Path a, @TempDir Path b) throws Exception {
        for (Path tmp : new Path[] {a, b}) {
            File root = tmp.toFile();
            writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n");
            write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        }
        // Same logical layout at two different absolute paths -> identical hash
        // (section 17: no absolute paths in the reproducibility key).
        String ha = Lockfile.hashOf(MultiModuleDependencies.namedConfigs(
            a.toFile(), discover(a.toFile())));
        String hb = Lockfile.hashOf(MultiModuleDependencies.namedConfigs(
            b.toFile(), discover(b.toFile())));
        assertEquals(ha, hb);
    }

    @Test
    void staleRootLockFailsEnforcement(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n");
        write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        MultiModuleProject project = discover(root);
        new Lockfile(Lockfile.hashOf(MultiModuleDependencies.namedConfigs(root, project)),
            java.util.Collections.emptyMap()).write(root);
        MultiModuleDependencies.enforceRootLock(project, root); // matches: no throw

        // Change the module config without refreshing -> stale lock, LCK-0003.
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n[project.properties]\n\"k\" = \"v\"\n");
        MultiModuleProject changed = discover(root);
        ForgeException ex = assertThrows(ForgeException.class,
            () -> MultiModuleDependencies.enforceRootLock(changed, root));
        assertEquals("FORGE-LCK-0003", ex.getCode());
    }

    @Test
    void missingRootLockIsAllowedForBootstrap(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n");
        write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");

        // No forge.lock on disk: enforcement is a no-op so early projects can build.
        MultiModuleDependencies.enforceRootLock(discover(root), root);
    }

    @Test
    void isMultiModuleDetectsDeclaredModules(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        writeRoot(root, "[project.modules]\n\"example.core\" = \"1.0.0\"\n");
        write(new File(root, "core/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        ProjectConfig multi = ConfigReader.loadConfig(new File(root, "forge.toml"));
        assertTrue(MultiModuleDependencies.isMultiModule(multi));

        File single = new File(tmp.toFile(), "single");
        write(new File(single, "forge.toml"), "forge = \"0.1\"\n[project]\nname = \"solo\"\n");
        assertFalse(MultiModuleDependencies.isMultiModule(
            ConfigReader.loadConfig(new File(single, "forge.toml"))));
    }
}
