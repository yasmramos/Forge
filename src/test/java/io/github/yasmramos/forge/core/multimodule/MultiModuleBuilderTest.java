package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end multi-module build (AGENT.md section 5): two modules where one
 * consumes the other by Maven coordinate. Asserts the real pipeline compiles
 * both, and that the consumer's compiled classes actually reference the
 * producer's code (proving the coordinate resolved to the freshly built jar,
 * not a stale or missing artifact).
 *
 * <p>No network: the modules declare no external dependencies.</p>
 */
class MultiModuleBuilderTest {

    private static void write(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void buildsTwoModulesWhereOneDependsOnTheOther(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\nname = \"multi\"\nversion = \"1.0.0\"\ngroup = \"com.example\"\n"
                + "[project.modules]\n\"example.core\" = \"${project.version}\"\n"
                + "\"example.app\" = \"${project.version}\"\n"
                + "[toolchain]\nlanguage = \"11\"\n"
                + "[build]\nthreads = 1\n");

        write(new File(root, "core/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.core\"\n");
        write(new File(root, "core/src/main/java/com/example/core/Calculator.java"),
            "package com.example.core;\n"
                + "public final class Calculator {\n"
                + "  private Calculator() {}\n"
                + "  public static int add(int a, int b) { return a + b; }\n"
                + "}\n");

        write(new File(root, "app/forge.toml"),
            "forge = \"0.1\"\n[project]\nname = \"example.app\"\n"
                + "[dependencies.compile]\n\"com.example:example.core\" = \"${project.version}\"\n");
        write(new File(root, "app/src/main/java/com/example/app/Main.java"),
            "package com.example.app;\n"
                + "import com.example.core.Calculator;\n"
                + "public final class Main {\n"
                + "  private Main() {}\n"
                + "  public static int run() { return Calculator.add(2, 3); }\n"
                + "}\n");

        ProjectConfig rootConfig = ConfigReader.loadConfig(new File(root, "forge.toml"));
        MultiModuleBuilder builder = new MultiModuleBuilder(root, rootConfig);
        MultiModuleBuilder.Report report = builder.build(null);

        assertTrue(report.isSuccess(), "both modules must build");
        assertEquals(2, report.getResults().size());

        // Both jars produced under their own module directory, not the root's.
        Path coreJar = tmp.resolve("core/target/forge-output/forge-main.jar");
        Path appJar = tmp.resolve("app/target/forge-output/forge-main.jar");
        assertTrue(Files.isRegularFile(coreJar), "core jar at " + coreJar);
        assertTrue(Files.isRegularFile(appJar), "app jar at " + appJar);

        // The app class must have been compiled against core's classes, which
        // only works if the coordinate resolved to the freshly built workspace jar.
        assertTrue(Files.isRegularFile(
            tmp.resolve("app/target/classes/com/example/app/Main.class")),
            "app main class compiled");

        // The workspace holds both module artifacts for later commands.
        assertTrue(Files.isRegularFile(tmp.resolve(".forge/workspace/example.core-1.0.0.jar")));
        assertTrue(Files.isRegularFile(tmp.resolve(".forge/workspace/example.core-1.0.0.pom")));
    }

    @Test
    void partialBuildSkipsUnrelatedModules(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\nname = \"multi\"\nversion = \"1.0.0\"\ngroup = \"com.example\"\n"
                + "[project.modules]\n\"a\" = \"1.0.0\"\n\"b\" = \"1.0.0\"\n"
                + "[toolchain]\nlanguage = \"11\"\n[build]\nthreads = 1\n");
        write(new File(root, "a/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"a\"\n");
        write(new File(root, "a/src/main/java/com/example/A.java"),
            "package com.example;\npublic class A {}\n");
        write(new File(root, "b/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"b\"\n");
        write(new File(root, "b/src/main/java/com/example/B.java"),
            "package com.example;\npublic class B {}\n");

        ProjectConfig rootConfig = ConfigReader.loadConfig(new File(root, "forge.toml"));
        MultiModuleBuilder.Report report = new MultiModuleBuilder(root, rootConfig).build("a");

        assertTrue(report.isSuccess());
        assertEquals(1, report.getResults().size(), "only module a is built");
        assertEquals("a", report.getResults().get(0).getName());
        assertTrue(Files.isRegularFile(tmp.resolve("a/target/forge-output/forge-main.jar")));
        assertTrue(!Files.exists(tmp.resolve("b/target")), "b must not be built");
    }

    @Test
    void failureInOneModuleStopsDependentModules(@TempDir Path tmp) throws Exception {
        File root = tmp.toFile();
        write(new File(root, "forge.toml"),
            "forge = \"0.1\"\n"
                + "[project]\nname = \"multi\"\nversion = \"1.0.0\"\ngroup = \"com.example\"\n"
                + "[project.modules]\n\"bad\" = \"1.0.0\"\n\"good\" = \"1.0.0\"\n"
                + "[toolchain]\nlanguage = \"11\"\n[build]\nthreads = 1\n");
        // 'bad' has a syntax error, so its build fails.
        write(new File(root, "bad/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"bad\"\n");
        write(new File(root, "bad/src/main/java/com/example/Bad.java"),
            "package com.example;\npublic class Bad { this is not java }\n");
        // 'good' depends on 'bad': it must not even be attempted.
        write(new File(root, "good/forge.toml"), "forge = \"0.1\"\n[project]\nname = \"good\"\n"
            + "[dependencies.compile]\n\"com.example:bad\" = \"1.0.0\"\n");
        write(new File(root, "good/src/main/java/com/example/Good.java"),
            "package com.example;\npublic class Good {}\n");

        ProjectConfig rootConfig = ConfigReader.loadConfig(new File(root, "forge.toml"));
        MultiModuleBuilder.Report report = new MultiModuleBuilder(root, rootConfig).build(null);

        assertTrue(!report.isSuccess(), "a failed module must fail the whole build");
        assertEquals(1, report.getResults().size(),
            "the dependent module must not be attempted after its dependency failed");
        assertEquals("bad", report.getResults().get(0).getName());
        assertTrue(!Files.exists(tmp.resolve("good/target")), "good must not be built");
    }
}
