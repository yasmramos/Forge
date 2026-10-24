package io.github.yasmramos.forge.utils;

import com.electronwill.nightconfig.core.Config;

import io.github.yasmramos.forge.model.ProjectConfig;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the TOML-based {@link ConfigReader} (NightConfig parser).
 */
class ConfigReaderTest {

    @Test
    void testConfigReaderInstantiation() {
        ConfigReader reader = new ConfigReader();
        assertNotNull(reader, "ConfigReader should be instantiable");
    }

    @Test
    void testConfigReaderHasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            new ConfigReader();
        }, "ConfigReader should have a working default constructor");
    }

    @Test
    void testParsesProjectSectionAndSchemaVersion() {
        Config root = Config.inMemory();
        root.set("forge", "0.1");
        root.set("project", Config.inMemory());
        root.set(java.util.Arrays.asList("project", "name"), "awesome");
        root.set(java.util.Arrays.asList("project", "version"), "1.0.0");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("0.1", config.getSchemaVersion());
        assertEquals("awesome", config.getName());
        assertEquals("1.0.0", config.getVersion());
    }

    @Test
    void testPropertyInterpolationInDependencies() {
        Config root = Config.inMemory();
        root.set("project.properties", Config.inMemory());
        root.set(java.util.Arrays.asList("project", "properties", "slf4j.version"), "2.0.7");
        root.set("dependencies.compile", Config.inMemory());
        root.set(java.util.Arrays.asList("dependencies", "compile", "org.slf4j:slf4j-api"),
                "${slf4j.version}");

        ProjectConfig config = ConfigReader.parseConfig(root);

        Map<String, Object> deps = config.getDependencies();
        assertTrue(deps.containsKey("org.slf4j:slf4j-api"));

        @SuppressWarnings("unchecked")
        Map<String, Object> dep = (Map<String, Object>) deps.get("org.slf4j:slf4j-api");
        assertEquals("2.0.7", dep.get("version"));
        assertEquals("maven", dep.get("type"));
        assertEquals("compile", dep.get("scope"));
    }

    @Test
    void testScopedSourcesPopulateLegacySourcePaths() {
        Config root = Config.inMemory();
        root.set("source.main", Config.inMemory());
        root.set(java.util.Arrays.asList("source", "main", "java"),
                java.util.Arrays.asList("src/main/java"));
        root.set(java.util.Arrays.asList("source", "main", "resources"),
                java.util.Arrays.asList("src/main/resources"));

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals(java.util.Arrays.asList("src/main/java"), config.getMainJavaSources());
        assertEquals("src/main/java", config.getSourceDirectory());
        assertTrue(config.getSourcePaths().contains("src/main/java"));
        assertTrue(config.getSourcePaths().contains("src/main/resources"));
    }

    @Test
    void testToolchainSetsSourceAndTargetCompatibility() {
        Config root = Config.inMemory();
        root.set("toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("toolchain", "language"), "11");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("11", config.getSourceCompatibility());
        assertEquals("11", config.getTargetCompatibility());
    }

    @Test
    void testBuildSectionUsesSnakeCaseKeys() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "incremental"), false);
        root.set(java.util.Arrays.asList("build", "cache_enabled"), false);
        root.set(java.util.Arrays.asList("build", "threads"), 4);

        ProjectConfig config = ConfigReader.parseConfig(root);
        ProjectConfig.BuildSettings settings = config.getBuildSettings();

        assertFalse(settings.isIncremental());
        assertFalse(settings.isCacheEnabled());
        assertEquals(4, settings.getThreads());
    }

    @Test
    void testZeroThreadsMeansAuto() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "threads"), 0);

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals(Runtime.getRuntime().availableProcessors(),
                config.getBuildSettings().getThreads());
    }

    @Test
    void testExecSection() {
        Config root = Config.inMemory();
        root.set("exec", Config.inMemory());
        root.set(java.util.Arrays.asList("exec", "main"), "com.example.Main");
        root.set(java.util.Arrays.asList("exec", "args"), java.util.Arrays.asList("--verbose"));
        root.set(java.util.Arrays.asList("exec", "jvm_args"), java.util.Arrays.asList("-Xmx2g"));

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("com.example.Main", config.getMainClass());
        assertEquals(java.util.Arrays.asList("--verbose"), config.getExecArgs());
        assertEquals(java.util.Arrays.asList("-Xmx2g"), config.getJvmArgs());
    }

    @Test
    void testExpandLeavesUnknownPlaceholdersUntouched() {
        Map<String, String> props = Map.of("a", "1");
        assertEquals("v1-${b}", ConfigReader.expand("v${a}-${b}", props));
    }
}
