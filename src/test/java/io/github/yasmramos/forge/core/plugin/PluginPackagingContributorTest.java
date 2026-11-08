package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.task.BuildEnvironment;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskContext;
import io.github.yasmramos.forge.core.task.TaskType;
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
 * Tests that a project selecting a plugin-provided packaging type has its
 * artifact produced by that plugin (AGENT.md section 4: "register packaging
 * types"). A project selecting a type no loaded plugin provides fails at
 * configure time with an actionable error rather than silently producing a jar.
 */
class PluginPackagingContributorTest {

    private static final String DESCRIPTOR = "schema = \"0.1\"\n[plugin]\n"
        + "id = \"io.example.pack\"\nname = \"pack\"\nversion = \"1.0.0\"\n"
        + "api = \">=0.1 <1.0\"\nmain = \"p.L\"\n";

    /** A packaging type "war" that writes its declared artifact name. */
    private static final String PACKAGING_PLUGIN =
        "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
        + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){"
        + "   c.registerPackagingType(\"war\", new io.github.yasmramos.forge.plugin.PluginPackagingType(){"
        + "     public String id(){ return \"war\"; }"
        + "     public String artifactFileName(){ return \"forge-main.war\"; }"
        + "     public io.github.yasmramos.forge.plugin.PluginPackagingResult packageArtifact("
        + "         io.github.yasmramos.forge.plugin.PluginPackagingRequest r){"
        + "       try {"
        + "         java.io.File out = new java.io.File(r.outputDirectory(), artifactFileName());"
        + "         out.getParentFile().mkdirs();"
        + "         java.nio.file.Files.write(out.toPath(), \"war\".getBytes());"
        + "       } catch (java.io.IOException e) {"
        + "         return io.github.yasmramos.forge.plugin.PluginPackagingResult.failure(e.toString()); }"
        + "       return io.github.yasmramos.forge.plugin.PluginPackagingResult.success(); }"
        + "   });"
        + " }"
        + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"packaging-type:war\"); } }";

    private static LoadedPlugins load(Path dir, String source, String provides) throws Exception {
        File jar = new PluginJarBuilder(dir).build("p.L", source, DESCRIPTOR + provides).jar;
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader()).load(Collections.singletonList(jar));
    }

    private static ProjectConfig config(String packaging) {
        ProjectConfig config = new ProjectConfig();
        config.setName("pack-project");
        config.setVersion("1.0.0");
        config.setPackaging(packaging);
        return config;
    }

    private static PluginPackagingContributor contributor(LoadedPlugins plugins, ProjectConfig config, Path root) {
        return new PluginPackagingContributor(plugins, config, root.toFile(),
            root.resolve("target/classes"), BuildEnvironment.forPlugins(plugins.idVersions()));
    }

    @Test
    void selectedTypeProducesItsArtifact(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        LoadedPlugins plugins = load(dir, PACKAGING_PLUGIN,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"war\"\n");
        PluginPackagingContributor c = contributor(plugins, config("war"), dir);

        assertTrue(c.isSelected());
        List<ForgeTask> tasks = c.tasks();
        assertEquals(1, tasks.size());
        ForgeTask task = tasks.get(0);
        assertEquals("package", task.getName());
        assertEquals("pluginPackage", task.getType().getName());
        assertTrue(task.getDependsOn().contains("test"), "must depend on the built-in test task");

        TaskContext ctx = new TaskContext(dir.toFile());
        assertTrue(task.getAction().execute(ctx).isSuccess());
        assertTrue(Files.isRegularFile(dir.resolve("target/forge-output/forge-main.war")),
            "the declared artifact must exist after packaging");
    }

    @Test
    void jarSelectionKeepsBuiltInPackaging(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, PACKAGING_PLUGIN,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"war\"\n");
        PluginPackagingContributor c = contributor(plugins, config("jar"), dir);
        assertFalse(c.isSelected());
        assertTrue(c.tasks().isEmpty(), "the built-in jar packaging must be left untouched");
    }

    @Test
    void unsetPackagingDefaultsToJar(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, PACKAGING_PLUGIN,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"war\"\n");
        PluginPackagingContributor c = contributor(plugins, config(null), dir);
        assertEquals("jar", c.selectedType());
        assertTrue(c.tasks().isEmpty());
    }

    @Test
    void selectingATypeNoPluginProvidesFailsAtConfigureTime(@TempDir Path dir) throws Exception {
        LoadedPlugins plugins = load(dir, PACKAGING_PLUGIN,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"war\"\n");
        PluginPackagingContributor c = contributor(plugins, config("ear"), dir);
        assertFalse(c.isSelected());
        ForgeException e = assertThrows(ForgeException.class, c::tasks);
        assertEquals("FORGE-PLG-0013", e.getCode());
    }

    @Test
    void packagingTypeWithoutImplementationCannotPackage(@TempDir Path dir) throws Exception {
        String src = "package p; public class L implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerPackagingType(\"ear\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"packaging-type:ear\"); } }";
        LoadedPlugins plugins = load(dir, src,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"ear\"\n");
        PluginPackagingContributor c = contributor(plugins, config("ear"), dir);
        assertFalse(c.isSelected(), "a type without an implementation is not usable");
        assertThrows(ForgeException.class, c::tasks);
    }

    @Test
    void packagingTaskIsContentKeyedAndCachedAcrossRuns(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        LoadedPlugins plugins = load(dir, PACKAGING_PLUGIN,
            "[[provides]]\ntype = \"packaging-type\"\nname = \"war\"\n");
        ForgeTask task = contributor(plugins, config("war"), dir).tasks().get(0);

        // The task depends on the built-in "test" task, which this focused test
        // does not build; provide it so the graph validates and runs.
        io.github.yasmramos.forge.core.graph.TaskGraph graph =
            new io.github.yasmramos.forge.core.graph.TaskGraph();
        graph.add(new ForgeTask("test", new TaskType("test")));
        graph.add(task);
        graph.validate();
        io.github.yasmramos.forge.core.cache.TaskCache cache =
            new io.github.yasmramos.forge.core.cache.TaskCache(dir.toFile());
        io.github.yasmramos.forge.core.graph.TaskScheduler scheduler =
            new io.github.yasmramos.forge.core.graph.TaskScheduler(1);

        assertEquals(2, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).executed());
        assertEquals(2, scheduler.execute(graph, new TaskContext(dir.toFile()), cache).cacheHits(),
            "unchanged inputs must hit the cache");
    }
}
