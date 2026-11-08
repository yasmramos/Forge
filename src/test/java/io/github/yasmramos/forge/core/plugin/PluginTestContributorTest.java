package io.github.yasmramos.forge.core.plugin;

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
 * Tests that plugin-registered test runners actually run tests (AGENT.md
 * section 4: "register test runners"). A runner registered with an
 * implementation contributes a test task that discovers the tests it claims by
 * extension; a runner without an implementation contributes nothing.
 */
class PluginTestContributorTest {

    private static final String DESCRIPTOR = "schema = \"0.1\"\n[plugin]\n"
        + "id = \"io.example.tests\"\nname = \"tests\"\nversion = \"1.0.0\"\n"
        + "api = \">=0.1 <1.0\"\nmain = \"p.L\"\n";

    /** A runner for ".lang" that reports how many sources it was handed. */
    private static final String RUNNER_PLUGIN =
        "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
        + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
        + "   c.registerTestRunner(\"lang\", new io.github.yasmramos.forge.plugin.PluginTestRunner(){"
        + "     public String testSourceExtension(){ return \"lang\"; }"
        + "     public io.github.yasmramos.forge.plugin.PluginTestRunResult run("
        + "         io.github.yasmramos.forge.plugin.PluginTestRunRequest r){"
        + "       try {"
        + "         java.io.File out = new java.io.File(r.outputDirectory(), \"ran.txt\");"
        + "         out.getParentFile().mkdirs();"
        + "         java.nio.file.Files.write(out.toPath(),"
        + "           (\"n=\" + r.sourceFiles().size()).getBytes());"
        + "       } catch (java.io.IOException e) {"
        + "         return io.github.yasmramos.forge.plugin.PluginTestRunResult.failure(0,0,e.toString()); }"
        + "       return io.github.yasmramos.forge.plugin.PluginTestRunResult.success("
        + "         r.sourceFiles().size(), r.sourceFiles().size()); }"
        + "   });"
        + " }"
        + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"test-runner:lang\"); } }";

    private static LoadedPlugins load(Path dir, String source, String provides) throws Exception {
        File jar = new PluginJarBuilder(dir).build("p.L", source, DESCRIPTOR + provides).jar;
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader()).load(Collections.singletonList(jar));
    }

    private static PluginTestContributor contributor(LoadedPlugins plugins, Path root, Path classes) {
        ProjectConfig config = new ProjectConfig();
        config.setName("tests-project");
        config.setVersion("1.0.0");
        config.setSourceDirectory(root.resolve("src/main/java").toString());
        return new PluginTestContributor(plugins, config, root.toFile(), classes,
            BuildEnvironment.forPlugins(plugins.idVersions()));
    }

    @Test
    void registeredRunnerRunsTheTestsItClaims(@TempDir Path dir) throws Exception {
        Path testDir = Files.createDirectories(dir.resolve("src/test/java/a"));
        Files.write(testDir.resolve("ATest.lang"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(testDir.resolve("BSpec.lang"), "b".getBytes(StandardCharsets.UTF_8));
        Files.write(testDir.resolve("C.java"), "c".getBytes(StandardCharsets.UTF_8));

        LoadedPlugins plugins = load(dir, RUNNER_PLUGIN,
            "[[provides]]\ntype = \"test-runner\"\nname = \"lang\"\n");
        Path classes = dir.resolve("target/classes");
        PluginTestContributor c = contributor(plugins, dir, classes);

        assertTrue(c.hasRunners());
        List<ForgeTask> tasks = c.tasks();
        assertEquals(1, tasks.size());
        ForgeTask task = tasks.get(0);
        assertEquals("langTest", task.getName());
        assertEquals("pluginTest", task.getType().getName());
        assertTrue(task.getDependsOn().contains("test"), "must depend on the built-in test task");

        TaskContext ctx = new TaskContext(dir.toFile());
        assertTrue(task.getAction().execute(ctx).isSuccess());
        assertEquals("n=2", new String(
            Files.readAllBytes(classes.getParent().resolve("plugin-test-results/ran.txt")),
            StandardCharsets.UTF_8), "only the two .lang tests must be passed");
    }

    @Test
    void runnerWithoutImplementationContributesNothing(@TempDir Path dir) throws Exception {
        String src = "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTestRunner(\"listed\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"test-runner:listed\"); } }";
        Files.createDirectories(dir.resolve("src/test/java"));
        LoadedPlugins plugins = load(dir, src,
            "[[provides]]\ntype = \"test-runner\"\nname = \"listed\"\n");

        PluginTestContributor c = contributor(plugins, dir, dir.resolve("target/classes"));
        assertFalse(c.hasRunners());
        assertTrue(c.tasks().isEmpty());
    }

    @Test
    void noTestsOfTheLanguageMeansNoTask(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        LoadedPlugins plugins = load(dir, RUNNER_PLUGIN,
            "[[provides]]\ntype = \"test-runner\"\nname = \"lang\"\n");
        PluginTestContributor c = contributor(plugins, dir, dir.resolve("target/classes"));
        assertTrue(c.hasRunners());
        assertTrue(c.tasks().isEmpty(), "no .lang tests -> no test task");
    }

    @Test
    void testTaskIsContentKeyedAndCachedAcrossRuns(@TempDir Path dir) throws Exception {
        Path testDir = Files.createDirectories(dir.resolve("src/test/java"));
        Files.write(testDir.resolve("ATest.lang"), "a".getBytes(StandardCharsets.UTF_8));

        LoadedPlugins plugins = load(dir, RUNNER_PLUGIN,
            "[[provides]]\ntype = \"test-runner\"\nname = \"lang\"\n");
        Path classes = dir.resolve("target/classes");
        ForgeTask task = contributor(plugins, dir, classes).tasks().get(0);

        // The task depends on the built-in "test" task, which this focused test
        // does not build; strip the dependency to exercise caching directly.
        io.github.yasmramos.forge.core.graph.TaskGraph graph =
            new io.github.yasmramos.forge.core.graph.TaskGraph();
        graph.add(new ForgeTask("test", new io.github.yasmramos.forge.core.task.TaskType("test")));
        graph.add(task);
        graph.validate();
        io.github.yasmramos.forge.core.cache.TaskCache cache =
            new io.github.yasmramos.forge.core.cache.TaskCache(dir.toFile());
        io.github.yasmramos.forge.core.graph.TaskScheduler scheduler =
            new io.github.yasmramos.forge.core.graph.TaskScheduler(1);

        assertEquals(2, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).executed());
        assertEquals(2, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).cacheHits(),
            "unchanged tests must hit the cache");
    }
}
