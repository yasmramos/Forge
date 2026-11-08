package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.ForgePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that plugin-registered task types actually contribute tasks to the
 * graph (AGENT.md sections 1 and 4): a type registered with a factory becomes
 * ForgeTask instances with declared inputs/outputs, and a bad contribution
 * fails at configure time with FORGE-PLG-0010.
 */
class PluginTaskContributorTest {

    private static String descriptor(String id, String main, String provides) {
        return "schema = \"0.1\"\n[plugin]\n"
            + "id = \"" + id + "\"\nname = \"" + id + "\"\nversion = \"1.0.0\"\n"
            + "api = \">=0.1 <1.0\"\nmain = \"" + main + "\"\n" + provides;
    }

    private static LoadedPlugins load(Path dir, String className, String source,
                                      String provides) throws Exception {
        File jar = new PluginJarBuilder(dir).build(className, source,
            descriptor("io.example.tasks", className, provides)).jar;
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader()).load(Collections.singletonList(jar));
    }

    private static PluginTaskContributor contributor(LoadedPlugins plugins, Path root) {
        ProjectConfig config = new ProjectConfig();
        config.setName("plugin-tasks");
        config.setVersion("1.0.0");
        config.setOutputDirectory(root.resolve("classes").toString());
        return new PluginTaskContributor(plugins, config, root.toFile(),
            io.github.yasmramos.forge.core.task.BuildEnvironment.forPlugins(plugins.idVersions()));
    }

    @Test
    void registeredTaskTypeContributesTasksToTheGraph(@TempDir Path dir) throws Exception {
        String src = "package p; public class T implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
            + "   c.registerTaskType(\"ktCompile\", new io.github.yasmramos.forge.plugin.PluginTaskFactory(){"
            + "     public java.util.List<io.github.yasmramos.forge.plugin.PluginTask> createTasks("
            + "         io.github.yasmramos.forge.plugin.PluginProject project){"
            + "       return java.util.Collections.singletonList("
            + "         new io.github.yasmramos.forge.plugin.PluginTask(){"
            + "           public String name(){ return \"ktCompile\"; }"
            + "           public java.util.List<String> outputPaths(){ return java.util.Collections.singletonList(\"target/kt\"); }"
            + "           public io.github.yasmramos.forge.plugin.PluginTaskResult execute("
            + "               io.github.yasmramos.forge.plugin.PluginTaskContext ctx){"
            + "             ctx.put(\"ran\", Boolean.TRUE);"
            + "             return io.github.yasmramos.forge.plugin.PluginTaskResult.success(\"ok\"); }"
            + "         });"
            + "     }"
            + "   });"
            + " }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:ktCompile\"); } }";

        LoadedPlugins plugins = load(dir, "p.T", src,
            "[[provides]]\ntype = \"task-type\"\nname = \"ktCompile\"\n");
        PluginTaskContributor c = contributor(plugins, dir);

        assertTrue(c.hasTasks());
        assertEquals(1, c.tasks().size());
        io.github.yasmramos.forge.core.task.ForgeTask task = c.tasks().get(0);
        assertEquals("ktCompile", task.getName());
        assertEquals("ktCompile", task.getType().getName());
        assertEquals(1, task.getOutputs().size());
        assertEquals(Collections.singletonList("target/kt"),
            task.getOutputs().get(0).getRelativePaths());

        // The action runs the plugin's execute.
        io.github.yasmramos.forge.core.task.TaskContext ctx =
            new io.github.yasmramos.forge.core.task.TaskContext(dir.toFile());
        assertTrue(task.getAction().execute(ctx).isSuccess());
        assertEquals(Boolean.TRUE, ctx.get("ran"));
    }

    @Test
    void contributedTaskRunsInTheGraphAndIsCachedByContent(@TempDir Path dir) throws Exception {
        // AGENT.md sections 1 and 3: a plugin task is a first-class graph node -
        // it executes on a miss and is served from cache on a re-run, keyed by
        // its declared inputs (content, never time).
        String src = "package p; public class G implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
            + "   c.registerTaskType(\"gen\", new io.github.yasmramos.forge.plugin.PluginTaskFactory(){"
            + "     public java.util.List<io.github.yasmramos.forge.plugin.PluginTask> createTasks("
            + "         io.github.yasmramos.forge.plugin.PluginProject project){"
            + "       return java.util.Collections.singletonList("
            + "         new io.github.yasmramos.forge.plugin.PluginTask(){"
            + "           public String name(){ return \"gen\"; }"
            + "           public java.util.Map<String,String> inputValues(){ return java.util.Collections.singletonMap(\"v\", \"1\"); }"
            + "           public io.github.yasmramos.forge.plugin.PluginTaskResult execute("
            + "               io.github.yasmramos.forge.plugin.PluginTaskContext ctx){"
            + "             new java.io.File(ctx.project().buildDirectory(), \"gen.txt\").getParentFile().mkdirs();"
            + "             try { java.nio.file.Files.write(new java.io.File(ctx.project().buildDirectory(), \"gen.txt\").toPath(), \"x\".getBytes()); }"
            + "             catch (java.io.IOException e) { return io.github.yasmramos.forge.plugin.PluginTaskResult.failure(e.toString()); }"
            + "             return io.github.yasmramos.forge.plugin.PluginTaskResult.success(); }"
            + "         }); } });"
            + " }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:gen\"); } }";
        LoadedPlugins plugins = load(dir, "p.G", src,
            "[[provides]]\ntype = \"task-type\"\nname = \"gen\"\n");

        io.github.yasmramos.forge.core.task.ForgeTask contributed =
            contributor(plugins, dir).tasks().get(0);
        io.github.yasmramos.forge.core.graph.TaskGraph graph =
            new io.github.yasmramos.forge.core.graph.TaskGraph();
        graph.add(contributed);
        graph.validate();

        io.github.yasmramos.forge.core.cache.TaskCache cache =
            new io.github.yasmramos.forge.core.cache.TaskCache(dir.toFile());
        io.github.yasmramos.forge.core.graph.TaskScheduler scheduler =
            new io.github.yasmramos.forge.core.graph.TaskScheduler(1);

        io.github.yasmramos.forge.core.graph.TaskScheduler.Report first = scheduler.execute(
            graph, new io.github.yasmramos.forge.core.task.TaskContext(dir.toFile()), cache);
        assertEquals(1, first.executed(), "first run must execute the plugin task");
        assertTrue(new File(dir.toFile(), "gen.txt").exists(),
            "the plugin task's side effect must be present");

        io.github.yasmramos.forge.core.graph.TaskScheduler.Report second = scheduler.execute(
            graph, new io.github.yasmramos.forge.core.task.TaskContext(dir.toFile()), cache);
        assertEquals(1, second.cacheHits(), "unchanged inputs must hit the cache");
    }

    @Test
    void typeWithoutFactoryContributesNothing(@TempDir Path dir) throws Exception {
        String src = "package p; public class N implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTaskType(\"listed\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:listed\"); } }";
        LoadedPlugins plugins = load(dir, "p.N", src,
            "[[provides]]\ntype = \"task-type\"\nname = \"listed\"\n");

        PluginTaskContributor c = contributor(plugins, dir);
        assertFalse(c.hasTasks());
        assertTrue(c.tasks().isEmpty());
    }

    @Test
    void shadowingABuiltInTaskFailsWithPlg0010(@TempDir Path dir) throws Exception {
        String src = "package p; public class S implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
            + "   c.registerTaskType(\"shadow\", new io.github.yasmramos.forge.plugin.PluginTaskFactory(){"
            + "     public java.util.List<io.github.yasmramos.forge.plugin.PluginTask> createTasks("
            + "         io.github.yasmramos.forge.plugin.PluginProject project){"
            + "       return java.util.Collections.singletonList("
            + "         new io.github.yasmramos.forge.plugin.PluginTask(){"
            + "           public String name(){ return \"compile\"; }"
            + "           public io.github.yasmramos.forge.plugin.PluginTaskResult execute("
            + "               io.github.yasmramos.forge.plugin.PluginTaskContext ctx){"
            + "             return io.github.yasmramos.forge.plugin.PluginTaskResult.success(); }"
            + "         }); } });"
            + " }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:shadow\"); } }";
        LoadedPlugins plugins = load(dir, "p.S", src,
            "[[provides]]\ntype = \"task-type\"\nname = \"shadow\"\n");

        ForgeException e = assertThrows(ForgeException.class,
            () -> contributor(plugins, dir).tasks());
        assertEquals("FORGE-PLG-0010", e.getCode());
    }

    @Test
    void duplicateTaskNamesFailWithPlg0010(@TempDir Path dir) throws Exception {
        String src = "package p; public class D implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
            + "   c.registerTaskType(\"dup\", new io.github.yasmramos.forge.plugin.PluginTaskFactory(){"
            + "     public java.util.List<io.github.yasmramos.forge.plugin.PluginTask> createTasks("
            + "         io.github.yasmramos.forge.plugin.PluginProject project){"
            + "       io.github.yasmramos.forge.plugin.PluginTask one = new io.github.yasmramos.forge.plugin.PluginTask(){"
            + "           public String name(){ return \"same\"; }"
            + "           public io.github.yasmramos.forge.plugin.PluginTaskResult execute("
            + "               io.github.yasmramos.forge.plugin.PluginTaskContext ctx){"
            + "             return io.github.yasmramos.forge.plugin.PluginTaskResult.success(); } };"
            + "       return java.util.Arrays.asList(one, one); } });"
            + " }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:dup\"); } }";
        LoadedPlugins plugins = load(dir, "p.D", src,
            "[[provides]]\ntype = \"task-type\"\nname = \"dup\"\n");

        ForgeException e = assertThrows(ForgeException.class,
            () -> contributor(plugins, dir).tasks());
        assertEquals("FORGE-PLG-0010", e.getCode());
    }
}
