package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.task.BuildEnvironment;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskContext;
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
 * Tests that plugin-registered compilers actually compile sources (AGENT.md
 * section 4: "register compilers for languages"). A language registered with a
 * compiler implementation contributes a compile task that discovers the
 * sources it claims by extension; a language without an implementation
 * contributes nothing.
 */
class PluginCompilationContributorTest {

    private static final String DESCRIPTOR = "schema = \"0.1\"\n[plugin]\n"
        + "id = \"io.example.lang\"\nname = \"lang\"\nversion = \"1.0.0\"\n"
        + "api = \">=0.1 <1.0\"\nmain = \"p.L\"\n";

    /** A plugin that registers a compiler for ".lang" whose "compilation" writes a marker file. */
    private static final String COMPILER_PLUGIN =
        "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
        + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
        + "   c.registerCompiler(\"lang\", new io.github.yasmramos.forge.plugin.PluginCompiler(){"
        + "     public String sourceExtension(){ return \"lang\"; }"
        + "     public io.github.yasmramos.forge.plugin.PluginCompilationResult compile("
        + "         io.github.yasmramos.forge.plugin.PluginCompilationRequest r){"
        + "       try {"
        + "         new java.io.File(r.outputDirectory(), \"compiled.txt\").getParentFile().mkdirs();"
        + "         java.nio.file.Files.write(new java.io.File(r.outputDirectory(), \"compiled.txt\").toPath(),"
        + "           (\"n=\" + r.sourceFiles().size()).getBytes());"
        + "       } catch (java.io.IOException e) {"
        + "         return io.github.yasmramos.forge.plugin.PluginCompilationResult.failure(e.toString()); }"
        + "       return io.github.yasmramos.forge.plugin.PluginCompilationResult.success(); }"
        + "   });"
        + " }"
        + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"compiler:lang\"); } }";

    private static LoadedPlugins load(Path dir, String source, String provides) throws Exception {
        File jar = new PluginJarBuilder(dir).build("p.L", source, DESCRIPTOR + provides).jar;
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader()).load(Collections.singletonList(jar));
    }

    private static PluginCompilationContributor contributor(LoadedPlugins plugins, Path root, Path classes) {
        ProjectConfig config = new ProjectConfig();
        config.setName("lang-project");
        config.setVersion("1.0.0");
        config.setSourceDirectory(root.resolve("src").toString());
        return new PluginCompilationContributor(plugins, config, root.toFile(), classes,
            BuildEnvironment.forPlugins(plugins.idVersions()));
    }

    @Test
    void registeredCompilerCompilesSourcesItClaims(@TempDir Path dir) throws Exception {
        // Two .lang sources and one decoy .java: only the claimed extension is compiled.
        Path src = Files.createDirectories(dir.resolve("src/a"));
        Files.write(src.resolve("A.lang"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("B.lang"), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("C.java"), "c".getBytes(StandardCharsets.UTF_8));

        LoadedPlugins plugins = load(dir, COMPILER_PLUGIN,
            "[[provides]]\ntype = \"compiler\"\nname = \"lang\"\n");
        Path classes = dir.resolve("target/classes");
        PluginCompilationContributor c = contributor(plugins, dir, classes);

        assertTrue(c.hasCompilers());
        List<ForgeTask> tasks = c.tasks();
        assertEquals(1, tasks.size());
        ForgeTask task = tasks.get(0);
        assertEquals("langCompile", task.getName());
        assertEquals("pluginCompile", task.getType().getName());
        assertEquals(1, task.getOutputs().size());

        TaskContext ctx = new TaskContext(dir.toFile());
        assertTrue(task.getAction().execute(ctx).isSuccess());
        assertEquals("n=2", new String(Files.readAllBytes(classes.resolve("compiled.txt")),
            StandardCharsets.UTF_8), "only the two .lang sources must be passed");
    }

    @Test
    void languageWithoutCompilerContributesNothing(@TempDir Path dir) throws Exception {
        String src = "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerCompiler(\"listed\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"compiler:listed\"); } }";
        Files.createDirectories(dir.resolve("src"));
        LoadedPlugins plugins = load(dir, src,
            "[[provides]]\ntype = \"compiler\"\nname = \"listed\"\n");

        PluginCompilationContributor c = contributor(plugins, dir, dir.resolve("target/classes"));
        assertFalse(c.hasCompilers());
        assertTrue(c.tasks().isEmpty());
    }

    @Test
    void noSourcesOfTheLanguageMeansNoTask(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        LoadedPlugins plugins = load(dir, COMPILER_PLUGIN,
            "[[provides]]\ntype = \"compiler\"\nname = \"lang\"\n");
        PluginCompilationContributor c = contributor(plugins, dir, dir.resolve("target/classes"));
        assertTrue(c.hasCompilers());
        assertTrue(c.tasks().isEmpty(), "no .lang sources -> no compile task");
    }

    @Test
    void compileTaskIsContentKeyedAndCachedAcrossRuns(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.write(src.resolve("A.lang"), "a".getBytes(StandardCharsets.UTF_8));

        LoadedPlugins plugins = load(dir, COMPILER_PLUGIN,
            "[[provides]]\ntype = \"compiler\"\nname = \"lang\"\n");
        Path classes = dir.resolve("target/classes");
        ForgeTask task = contributor(plugins, dir, classes).tasks().get(0);

        io.github.yasmramos.forge.core.graph.TaskGraph graph =
            new io.github.yasmramos.forge.core.graph.TaskGraph();
        graph.add(task);
        graph.validate();
        io.github.yasmramos.forge.core.cache.TaskCache cache =
            new io.github.yasmramos.forge.core.cache.TaskCache(dir.toFile());
        io.github.yasmramos.forge.core.graph.TaskScheduler scheduler =
            new io.github.yasmramos.forge.core.graph.TaskScheduler(1);

        assertEquals(1, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).executed());
        assertEquals(1, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).cacheHits(),
            "unchanged sources must hit the cache");
    }
}
