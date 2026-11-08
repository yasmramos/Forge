package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.core.ForgeEngine.BuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for ForgeEngine
 */
class ForgeEngineTest {

    /**
     * Isolated build output directory for the whole test class. Without it,
     * engine builds would write compiled classes into the shared
     * target/classes tree and race with Surefire's own class loading.
     */
    @TempDir
    static Path sharedBuildDir;

    private ForgeEngine forgeEngine;
    private ProjectConfig mockConfig;

    @BeforeEach
    void setUp() {
        mockConfig = createMockConfig();
        mockConfig.setOutputDirectory(sharedBuildDir.resolve("classes").toString());
        forgeEngine = new ForgeEngine(mockConfig);
    }
    
    @Test
    void testForgeEngineCreation() {
        assertNotNull(forgeEngine);
    }
    
    @Test
    void testBuildMethodExists() {
        // Test that the build method exists and returns a BuildResult
        BuildResult result = forgeEngine.build();
        assertNotNull(result);
    }
    
    @Test
    void testBuildIncrementalMethodExists() {
        // Test that the buildIncremental method exists and returns a BuildResult
        BuildResult result = forgeEngine.buildIncremental();
        assertNotNull(result);
    }
    
    @Test
    void testCleanMethodExists() {
        // Test that the clean method exists and can be called
        assertDoesNotThrow(() -> forgeEngine.clean());
    }

    @Test
    void staleLockFailsTheDefaultBuild(@TempDir Path tmp) throws Exception {
        // A committed lock must match forge.toml; editing the config without
        // refreshing the lock must fail the build (AGENT.md section 7bis).
        java.io.File root = tmp.toFile();
        java.io.File toml = new java.io.File(root, "forge.toml");
        Path src = tmp.resolve("src/main/java");
        java.nio.file.Files.createDirectories(src);
        java.nio.file.Files.write(src.resolve("Hello.java"),
            "public class Hello { }".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        write(toml, "forge = \"0.1\"\n[project]\nname = \"lockcheck\"\nversion = \"1.0.0\"\n");
        ProjectConfig config = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        config.setSourceDirectory(src.toString());
        config.setOutputDirectory(tmp.resolve("classes").toString());
        new io.github.yasmramos.forge.core.resolver.LockfileGenerator(
                new java.io.File(root, "cache"), config.getRepositories())
            .generate(root, config)
            .write(root);

        // Positive control: a matching lock lets the build proceed.
        assertTrue(new ForgeEngine(config, root).build().isSuccess(),
            "build with a matching lock must succeed");

        // Now change forge.toml: config_hash no longer matches the lock.
        write(toml, "forge = \"0.1\"\n[project]\nname = \"lockcheck\"\nversion = \"1.0.0\"\n"
            + "[build]\nthreads = 2\n");
        ProjectConfig changed = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        changed.setSourceDirectory(src.toString());
        changed.setOutputDirectory(tmp.resolve("classes").toString());

        assertFalse(new ForgeEngine(changed, root).build().isSuccess(),
            "stale forge.lock must fail the build");
    }

    @Test
    void enforceExistingLockReportsLck0003OnConfigDrift(@TempDir Path tmp) throws Exception {
        java.io.File root = tmp.toFile();
        java.io.File toml = new java.io.File(root, "forge.toml");
        write(toml, "forge = \"0.1\"\n[project]\nname = \"lockcheck\"\nversion = \"1.0.0\"\n");
        ProjectConfig config = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        new io.github.yasmramos.forge.core.resolver.LockfileGenerator(
                new java.io.File(root, "cache"), config.getRepositories())
            .generate(root, config)
            .write(root);
        write(toml, "forge = \"0.1\"\n[project]\nname = \"lockcheck\"\nversion = \"2.0.0\"\n");

        io.github.yasmramos.forge.cli.error.ForgeException ex = assertThrows(
            io.github.yasmramos.forge.cli.error.ForgeException.class,
            () -> io.github.yasmramos.forge.core.resolver.LockfileGenerator
                .enforceExistingLock(root));
        assertEquals("FORGE-LCK-0003", ex.getCode());
    }

    @Test
    void missingLockIsNotEnforced(@TempDir Path tmp) {
        // Bootstrap: with no forge.lock yet, enforcement is a no-op.
        assertDoesNotThrow(() -> io.github.yasmramos.forge.core.resolver.LockfileGenerator
            .enforceExistingLock(tmp.toFile()));
    }

    @Test
    void incrementalBuildFailsOnBrokenSource(@TempDir Path tmp) throws Exception {
        // Regression: `forge build incremental` used to report success even when
        // compilation failed (BuildResult(true, ...) was hardcoded), so CI went
        // green on broken code. It must propagate the failure.
        java.io.File root = tmp.toFile();
        java.io.File toml = new java.io.File(root, "forge.toml");
        Path src = tmp.resolve("src/main/java");
        java.nio.file.Files.createDirectories(src);
        write(toml, "forge = \"0.1\"\n[project]\nname = \"inc\"\nversion = \"1.0.0\"\n");

        ProjectConfig config = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        config.setSourceDirectory(src.toString());
        config.setOutputDirectory(tmp.resolve("classes").toString());

        // Positive control: valid source compiles.
        write(src.resolve("A.java").toFile(), "package p;\npublic class A { public int f() { return 1; } }\n");
        assertTrue(new ForgeEngine(config, root).buildIncremental().isSuccess(),
            "valid source must build");

        // Broken source must fail the incremental build.
        write(src.resolve("A.java").toFile(),
            "package p;\npublic class A { public int f() { return \"x\"; } }\n");
        assertFalse(new ForgeEngine(config, root).buildIncremental().isSuccess(),
            "broken source must fail the incremental build");
    }

    private static void write(java.io.File file, String content) throws Exception {
        java.nio.file.Files.write(file.toPath(),
            content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void declaredPluginIsResolvedAtConfigureTime(@TempDir Path tmp) throws Exception {
        // AGENT.md sections 4 and 16bis: [plugins] in forge.toml is loaded at
        // configure time, before the graph runs. A declared but unresolvable
        // plugin must fail with FORGE-PLG-0008 and a message naming it - never a
        // silent skip.
        java.io.File root = tmp.toFile();
        java.io.File toml = new java.io.File(root, "forge.toml");
        Path src = tmp.resolve("src/main/java");
        java.nio.file.Files.createDirectories(src);
        write(src.resolve("Hello.java").toFile(), "public class Hello { }");
        write(toml, "forge = \"0.1\"\n[project]\nname = \"plug\"\nversion = \"1.0.0\"\n"
            + "[plugins]\n\"com.example.does.not:exist-plugin\" = \"9.9.9\"\n");

        ProjectConfig config = io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        config.setSourceDirectory(src.toString());
        config.setOutputDirectory(tmp.resolve("classes").toString());

        io.github.yasmramos.forge.cli.error.ForgeException ex = assertThrows(
            io.github.yasmramos.forge.cli.error.ForgeException.class,
            () -> new ForgeEngine(config, root).buildWithTaskGraph());
        assertEquals("FORGE-PLG-0008", ex.getCode());
        assertTrue(ex.getMessage().contains("com.example.does.not:exist-plugin"),
            "the error must name the plugin that failed to resolve: " + ex.getMessage());
    }

    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("test-project");
        config.setVersion("1.0.0");
        config.setSourcePaths(new ArrayList<>());
        return config;
    }
}