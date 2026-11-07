package io.github.yasmramos.forge.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ForgeCLI class.
 * Tests basic instantiation and structure.
 */
class CLITest {

    @Test
    void testCLIInstantiation() {
        String[] args = {};
        ForgeCLI cli = new ForgeCLI(args);
        assertNotNull(cli, "CLI should be instantiable");
    }

    @Test
    void testCLIWithEmptyArgs() {
        String[] args = {};
        assertDoesNotThrow(() -> {
            new ForgeCLI(args);
        }, "CLI should handle empty arguments");
    }

    @Test
    void testCLIWithNullArgs() {
        assertDoesNotThrow(() -> {
            new ForgeCLI(null);
        }, "CLI should handle null arguments");
    }

    @Test
    void unknownCommandReturnsUsageErrorCode() {
        ForgeCLI cli = new ForgeCLI(new String[] {"frobnicate"});
        int exit = cli.execute(null);
        assertEquals(1, exit, "unknown command is a user/config error");
    }

    @Test
    void buildWithoutProjectFailsWithStructuredError() {
        // No engine: the command needs a project, so it must not NPE but
        // return a user-facing error code (AGENT.md 10bis).
        ForgeCLI cli = new ForgeCLI(new String[] {"build"});
        assertEquals(1, cli.execute(null));
    }

    @Test
    void helpSucceedsWithoutProject() {
        ForgeCLI cli = new ForgeCLI(new String[] {"help"});
        assertEquals(0, cli.execute(null));
    }

    @Test
    void badBuildTypeReturnsUsageError() {
        ForgeCLI cli = new ForgeCLI(new String[] {"build", "nonsense"});
        assertEquals(1, cli.execute(null));
    }

    @Test
    void incrementalBuildReturnsNonZeroOnCompileFailure(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // Regression: the CLI used to ignore the incremental BuildResult and
        // always return 0, hiding compile failures from CI.
        java.io.File root = tmp.toFile();
        java.io.File toml = new java.io.File(root, "forge.toml");
        java.nio.file.Path src = tmp.resolve("src/main/java");
        java.nio.file.Files.createDirectories(src);
        java.nio.file.Files.write(toml.toPath(),
            ("forge = \"0.1\"\n[project]\nname = \"cli-inc\"\nversion = \"1.0.0\"\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(src.resolve("A.java"),
            "package p;\npublic class A { public int f() { return \"x\"; } }\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        io.github.yasmramos.forge.model.ProjectConfig config =
            io.github.yasmramos.forge.utils.ConfigReader.loadConfig(toml);
        config.setSourceDirectory(src.toString());
        config.setOutputDirectory(tmp.resolve("classes").toString());

        ForgeCLI cli = new ForgeCLI(new String[] {"build", "incremental"});
        int exit = cli.execute(new io.github.yasmramos.forge.core.ForgeEngine(config, root));
        assertEquals(1, exit, "a failing incremental build must exit non-zero");
    }
}
