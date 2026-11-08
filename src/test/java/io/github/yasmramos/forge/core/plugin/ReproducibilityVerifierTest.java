package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.packageinfo.ReproducibilityVerifier;
import io.github.yasmramos.forge.core.task.BuildEnvironment;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.ForgePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the double-run reproducibility check (AGENT.md section 17): a plugin
 * that declares {@code [compatibility] reproducible = true} but writes
 * non-deterministic output is detected, warned about by default and fails the
 * build in strict mode. A plugin whose output is stable passes, and a plugin
 * that does not claim reproducibility is never checked.
 */
class ReproducibilityVerifierTest {

    private static String descriptor(String id, boolean reproducible) {
        return "schema = \"0.1\"\n[plugin]\n"
            + "id = \"" + id + "\"\nname = \"" + id + "\"\nversion = \"1.0.0\"\n"
            + "api = \">=0.1 <1.0\"\nmain = \"p.T\"\n"
            + "[[provides]]\ntype = \"task-type\"\nname = \"gen\"\n"
            + "[compatibility]\nreproducible = " + reproducible + "\n";
    }

    /** A task type "gen" whose action writes {@code content} into the output dir. */
    private static String source(String contentExpr) {
        return "package p; public class T implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
            + "   c.registerTaskType(\"gen\", new io.github.yasmramos.forge.plugin.PluginTaskFactory(){"
            + "     public java.util.List<io.github.yasmramos.forge.plugin.PluginTask> createTasks("
            + "         io.github.yasmramos.forge.plugin.PluginProject project){"
            + "       return java.util.Collections.singletonList("
            + "         new io.github.yasmramos.forge.plugin.PluginTask(){"
            + "           public String name(){ return \"gen\"; }"
            + "           public java.util.List<String> outputPaths(){"
            + "             return java.util.Collections.singletonList(\"target/gen\"); }"
            + "           public io.github.yasmramos.forge.plugin.PluginTaskResult execute("
            + "               io.github.yasmramos.forge.plugin.PluginTaskContext ctx){"
            + "             try {"
            + "               java.io.File dir = new java.io.File(ctx.project().root(), \"target/gen\");"
            + "               dir.mkdirs();"
            + "               byte[] body = (" + contentExpr + ").getBytes(java.nio.charset.StandardCharsets.UTF_8);"
            + "               java.nio.file.Files.write(new java.io.File(dir, \"out.txt\").toPath(), body);"
            + "             } catch (java.io.IOException e) {"
            + "               return io.github.yasmramos.forge.plugin.PluginTaskResult.failure(e.toString()); }"
            + "             return io.github.yasmramos.forge.plugin.PluginTaskResult.success(); }"
            + "         }); } });"
            + " }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:gen\"); } }";
    }

    private static LoadedPlugins load(Path dir, String id, boolean reproducible,
                                      String contentExpr) throws Exception {
        File jar = new PluginJarBuilder(dir).build("p.T", source(contentExpr),
            descriptor(id, reproducible)).jar;
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader()).load(Collections.singletonList(jar));
    }

    private static List<ForgeTask> tasks(LoadedPlugins plugins, Path root) {
        ProjectConfig config = new ProjectConfig();
        config.setName("rpr");
        config.setVersion("1.0.0");
        config.setOutputDirectory(root.resolve("classes").toString());
        return new PluginTaskContributor(plugins, config, root.toFile(),
            BuildEnvironment.forPlugins(plugins.idVersions())).tasks();
    }

    @Test
    void stablePluginPassesTheCheck(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, "io.example.stable", true, "\"constant\"");
        ReproducibilityVerifier verifier = new ReproducibilityVerifier(plugins, dir.toFile());
        assertTrue(verifier.hasClaimingPlugins());

        List<ReproducibilityVerifier.Violation> violations = verifier.verify(tasks(plugins, dir));
        assertTrue(violations.isEmpty(), "a stable plugin must not be reported");
        assertTrue(Files.isRegularFile(dir.resolve("target/gen/out.txt")),
            "the artifact must remain after the check");
        assertEquals("constant", new String(Files.readAllBytes(dir.resolve("target/gen/out.txt")),
            StandardCharsets.UTF_8));
    }

    @Test
    void unstablePluginIsDetectedAndWarnsByDefault(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, "io.example.unstable", true,
            "java.util.UUID.randomUUID().toString()");
        ReproducibilityVerifier verifier = new ReproducibilityVerifier(plugins, dir.toFile());

        List<ReproducibilityVerifier.Violation> violations = verifier.verify(tasks(plugins, dir));
        assertEquals(1, violations.size(), "non-deterministic output must be detected");
        assertEquals("io.example.unstable", violations.get(0).getPluginId());
        assertEquals("gen", violations.get(0).getTaskName());
    }

    @Test
    void strictModeFailsTheBuild(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, "io.example.unstable", true,
            "java.util.UUID.randomUUID().toString()");
        ReproducibilityVerifier verifier =
            new ReproducibilityVerifier(plugins, dir.toFile(), true);

        ForgeException e = assertThrows(ForgeException.class,
            () -> verifier.verify(tasks(plugins, dir)));
        assertEquals("FORGE-RPR-0001", e.getCode());
    }

    @Test
    void nonClaimingPluginIsNotChecked(@TempDir Path dir) throws Exception {
        // Declares reproducible = false: even non-deterministic output is out of
        // scope, because the plugin is honest about it (section 17).
        LoadedPlugins plugins = load(dir, "io.example.honest", false,
            "java.util.UUID.randomUUID().toString()");
        ReproducibilityVerifier verifier = new ReproducibilityVerifier(plugins, dir.toFile());
        assertFalse(verifier.hasClaimingPlugins());
        assertTrue(verifier.verify(tasks(plugins, dir)).isEmpty());
    }
}
