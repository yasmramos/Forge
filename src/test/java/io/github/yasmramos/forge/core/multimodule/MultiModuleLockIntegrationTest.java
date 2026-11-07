package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the single root lock of a multi-module project (AGENT.md
 * sections 5 and 8bis). Hits Maven Central, mirroring LockfileGeneratorTest.
 */
class MultiModuleLockIntegrationTest {

    private static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rootLockCoversEveryModuleExternalDependency(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\nname = \"multi\"\nversion = \"1.0.0\"\ngroup = \"com.example\"\n"
                + "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
                + "\"example.app\" = \"${project.version}\"\n"
                + "[repositories]\ncentral = \"https://repo1.maven.org/maven2\"\n"
                + "[toolchain]\nlanguage = \"11\"\n");
        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n"
                + "[dependencies.compile]\n\"org.slf4j:slf4j-api\" = \"2.0.7\"\n");
        write(new File(root, "app/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
                + "[dependencies.compile]\n\"com.example:example.core\" = \"${project.version}\"\n");

        ProjectConfig rootConfig = ConfigReader.loadConfig(new File(root, "forge.toml"));
        MultiModuleProject project = MultiModuleProject.discover(root, rootConfig);

        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        Lockfile lock = MultiModuleDependencies.generateLock(project, root, rootConfig, repos);

        // The single root lock covers the external dependency of a module, even
        // though the root forge.toml declares no dependencies of its own.
        Lockfile.LockedDependency slf4j = lock.getDependencies().get("org.slf4j:slf4j-api");
        assertNotNull(slf4j, "the union of module deps must be locked: " + lock.getDependencies().keySet());
        assertEquals("2.0.7", slf4j.version);
        assertEquals(64, slf4j.sha256.length(), "checksum is mandatory (section 18)");
        // The inter-module coordinate is never locked as an external artifact.
        assertTrue(lock.getDependencies().keySet().stream()
            .noneMatch(k -> k.equals("com.example:example.core")));

        // Round-trip and verification against the combined config hash.
        lock.write(root);
        Lockfile reloaded = Lockfile.read(root);
        reloaded.verifyAgainstHash(Lockfile.hashOf(
            MultiModuleDependencies.namedConfigs(root, project)));
    }
}
