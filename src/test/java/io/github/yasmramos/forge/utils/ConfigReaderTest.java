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
    void testBuildSectionParsesRemoteCacheUrl() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "remote_cache"), "https://cache.example/forge");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("https://cache.example/forge",
            config.getBuildSettings().getRemoteCacheUrl());
    }

    @Test
    void testRemoteCacheUrlIsUnsetByDefault() {
        // FORGE_REMOTE_CACHE overrides the file (section 24); skip if the CI
        // environment sets it, otherwise the default must be null.
        String env = System.getenv("FORGE_REMOTE_CACHE");
        org.junit.jupiter.api.Assumptions.assumeTrue(env == null || env.trim().isEmpty());

        Config root = Config.inMemory();
        root.set("build", Config.inMemory());

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertNull(config.getBuildSettings().getRemoteCacheUrl());
    }

    @Test
    void testAuditSettingsParseAndDefault() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "audit_oss_index"), "https://mirror.example/oss");
        root.set(java.util.Arrays.asList("build", "audit_fail_on"), "critical");

        ProjectConfig.BuildSettings settings = ConfigReader.parseConfig(root).getBuildSettings();

        assertEquals("https://mirror.example/oss", settings.getAuditOssIndexUrl());
        assertEquals("critical", settings.getAuditFailOn());
    }

    @Test
    void testAuditFailOnDefaultsToHigh() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());

        assertEquals("high",
            ConfigReader.parseConfig(root).getBuildSettings().getAuditFailOn());
    }

    @Test
    void constraintsAreParsed() {
        Config root = Config.inMemory();
        root.set(java.util.Arrays.asList("constraints", "com.google.guava:guava"), "32.1.3-jre");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("32.1.3-jre", config.getConstraints().get("com.google.guava:guava"));
    }

    @Test
    void constraintsDefaultToEmpty() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertTrue(config.getConstraints().isEmpty());
    }

    @Test
    void reproducibleDefaultsToWarn() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("warn", config.getBuildSettings().getReproducible());
        assertFalse(config.getBuildSettings().isReproducibleStrict());
        assertFalse(config.getBuildSettings().isReproducibleOff());
    }

    @Test
    void strictReproducibilityIsParsed() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "reproducible"), "strict");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertTrue(config.getBuildSettings().isReproducibleStrict());
        assertFalse(config.getBuildSettings().isReproducibleOff());
    }

    @Test
    void reproducibilityOffIsParsed() {
        Config root = Config.inMemory();
        root.set("build", Config.inMemory());
        root.set(java.util.Arrays.asList("build", "reproducible"), "off");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertTrue(config.getBuildSettings().isReproducibleOff());
        assertFalse(config.getBuildSettings().isReproducibleStrict());
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

    @Test
    void testParsesPluginsAsCoordinates() {
        // AGENT.md sections 4/4bis: plugins are declared explicitly with a Maven
        // coordinate; what is in the file gets loaded, what is not, does not.
        Config root = Config.inMemory();
        root.set("plugins", Config.inMemory());
        root.set(java.util.Arrays.asList("plugins", "io.example.forge.kotlin"), "1.2.0");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("1.2.0", config.getPlugins().get("io.example.forge.kotlin"));
    }

    @Test
    void testPluginVersionIsInterpolated() {
        Config root = Config.inMemory();
        root.set("project.properties", Config.inMemory());
        root.set(java.util.Arrays.asList("project", "properties", "kotlin.plugin"), "1.2.0");
        root.set("plugins", Config.inMemory());
        root.set(java.util.Arrays.asList("plugins", "io.example.forge.kotlin"),
            "${kotlin.plugin}");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("1.2.0", config.getPlugins().get("io.example.forge.kotlin"));
    }

    @Test
    void testNoPluginsSectionMeansNoPlugins() {
        Config root = Config.inMemory();
        ProjectConfig config = ConfigReader.parseConfig(root);
        assertTrue(config.getPlugins().isEmpty());
    }

    @Test
    void testParsesPublishSection() {
        Config root = Config.inMemory();
        root.set("publish", Config.inMemory());
        root.set(java.util.Arrays.asList("publish", "license"), "Apache-2.0");
        root.set(java.util.Arrays.asList("publish", "license_url"),
            "https://www.apache.org/licenses/LICENSE-2.0");
        root.set(java.util.Arrays.asList("publish", "developers"), "jdoe");
        root.set(java.util.Arrays.asList("publish", "developer_names"), "Jane Doe");
        root.set(java.util.Arrays.asList("publish", "scm"),
            "scm:git:https://github.com/example/awesome.git");
        root.set(java.util.Arrays.asList("publish", "url"), "https://example.io/awesome");
        root.set(java.util.Arrays.asList("publish", "sign"), false);
        root.set("publish.snapshot", Config.inMemory());
        root.set(java.util.Arrays.asList("publish", "snapshot", "repository"), "ossrh-snapshots");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("Apache-2.0", config.getPublishSettings().getLicenseName());
        assertEquals("jdoe", config.getPublishSettings().getDeveloperIds());
        assertEquals("Jane Doe", config.getPublishSettings().getDeveloperNames());
        assertEquals("https://example.io/awesome", config.getPublishSettings().getUrl());
        assertFalse(config.getPublishSettings().isSign());
        assertEquals("ossrh-snapshots", config.getPublishSettings().getSnapshotRepository());
    }

    @Test
    void testPublishDefaultsWhenAbsent() {
        Config root = Config.inMemory();
        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("central", config.getPublishSettings().getRepository());
        assertTrue(config.getPublishSettings().isSign());
        assertTrue(config.getPublishSettings().isSbom());
    }

    // --- Variants (AGENT.md sections 8 and 8bis) ---

    @Test
    void variantOverlayReplacesScalarsAndMergesTables() {
        Config root = Config.inMemory();
        root.set("toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("toolchain", "language"), "11");
        root.set("compile", Config.inMemory());
        root.set(java.util.Arrays.asList("compile", "encoding"), "UTF-8");
        root.set("variant.prod", Config.inMemory());
        root.set("variant.prod.toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("variant", "prod", "toolchain", "language"), "17");
        root.set("variant.prod.compile", Config.inMemory());
        root.set(java.util.Arrays.asList("variant", "prod", "compile", "debug"), false);

        ProjectConfig config = ConfigReader.parseConfig(root, null, "prod");

        assertEquals("17", config.getSourceCompatibility());
        assertEquals("17", config.getTargetCompatibility());
        // The variant overrides one [compile] key without restating encoding.
        assertEquals("UTF-8", config.getBuildSettings().getEncoding());
        assertFalse(config.getBuildSettings().isDebug());
        assertEquals("prod", config.getSelectedVariant());
    }

    @Test
    void variantListIsReplacedNotAppended() {
        Config root = Config.inMemory();
        root.set("compile", Config.inMemory());
        root.set(java.util.Arrays.asList("compile", "args"),
            java.util.Arrays.asList("-Xlint:all"));
        root.set("variant.prod", Config.inMemory());
        root.set("variant.prod.compile", Config.inMemory());
        root.set(java.util.Arrays.asList("variant", "prod", "compile", "args"),
            java.util.Arrays.asList("-Xlint:none"));

        ProjectConfig config = ConfigReader.parseConfig(root, null, "prod");

        assertEquals(java.util.Arrays.asList("-Xlint:none"), config.getCompilerArgs());
    }

    @Test
    void unselectedVariantLeavesBaseConfigUntouched() {
        Config root = Config.inMemory();
        root.set("toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("toolchain", "language"), "11");
        root.set("variant.prod", Config.inMemory());
        root.set("variant.prod.toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("variant", "prod", "toolchain", "language"), "17");

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals("11", config.getSourceCompatibility());
        assertNull(config.getSelectedVariant());
        // The declared names are still visible so orchestration can distinguish
        // "unknown variant" from "variant not declared in this module".
        assertEquals(java.util.Collections.singleton("prod"), config.getVariantNames());
    }

    @Test
    void unknownVariantFallsBackToBaseButStaysUnknownToCaller() {
        Config root = Config.inMemory();
        root.set("toolchain", Config.inMemory());
        root.set(java.util.Arrays.asList("toolchain", "language"), "11");
        root.set("variant.prod", Config.inMemory());

        // The parser never invents a variant: an undeclared name leaves the base
        // configuration untouched and is reported as unknown (section 8bis).
        ProjectConfig config = ConfigReader.parseConfig(root, null, "benchmark");

        assertEquals("11", config.getSourceCompatibility());
        assertEquals("benchmark", config.getSelectedVariant());
        assertFalse(config.getVariantNames().contains("benchmark"));
        assertEquals(java.util.Collections.singleton("prod"), config.getVariantNames());
    }

    @Test
    void cacheScopeIdSeparatesVariants() {
        Config root = Config.inMemory();
        root.set("project", Config.inMemory());
        root.set(java.util.Arrays.asList("project", "name"), "awesome");
        root.set(java.util.Arrays.asList("project", "group"), "com.example");
        root.set("variant.prod", Config.inMemory());

        // A cached artifact produced under one variant must never be served to
        // another: the scope id carries the variant (AGENT.md sections 3, 8bis).
        assertEquals("com.example:awesome",
            ConfigReader.parseConfig(root).getCacheScopeId());
        assertEquals("com.example:awesome@prod",
            ConfigReader.parseConfig(root, null, "prod").getCacheScopeId());
    }

    @Test
    void testSectionDefaultsAreOff() {
        // Both [test] behaviours are opt-in: with no section, nothing changes.
        ProjectConfig config = ConfigReader.parseConfig(Config.inMemory());
        assertFalse(config.getTestSettings().isSelective());
        assertEquals(0, config.getTestSettings().getRetryFailed());
    }

    @Test
    void testSectionIsParsed() {
        Config root = Config.inMemory();
        root.set("test", Config.inMemory());
        root.set(java.util.Arrays.asList("test", "selective"), true);
        root.set(java.util.Arrays.asList("test", "retry_failed"), 2);

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertTrue(config.getTestSettings().isSelective());
        assertEquals(2, config.getTestSettings().getRetryFailed());
    }

    @Test
    void retryFailedIsNeverNegative() {
        Config root = Config.inMemory();
        root.set("test", Config.inMemory());
        root.set(java.util.Arrays.asList("test", "retry_failed"), -5);

        ProjectConfig config = ConfigReader.parseConfig(root);

        assertEquals(0, config.getTestSettings().getRetryFailed(),
            "a negative retry count is clamped to zero");
    }
}
