package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.ForgePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the wiring from {@code [plugins]} in {@code forge.toml} to the loader
 * (AGENT.md sections 4 and 4bis). A stub jar resolver stands in for Maven
 * Resolver, so the test is offline; the jars themselves are real plugin jars
 * built at test time.
 */
class PluginBootstrapTest {

    private static ProjectConfig configWith(Map<String, Object> plugins) {
        ProjectConfig config = new ProjectConfig();
        config.setName("bootstrap-test");
        config.setVersion("1.0.0");
        config.setPlugins(plugins);
        return config;
    }

    private static String descriptor(String id, String main) {
        return "schema = \"0.1\"\n[plugin]\n"
            + "id = \"" + id + "\"\nname = \"" + id + "\"\nversion = \"1.0.0\"\n"
            + "api = \">=0.1 <1.0\"\nmain = \"" + main + "\"\n"
            + "[[provides]]\ntype = \"task-type\"\nname = \"kt\"\n";
    }

    @Test
    void noDeclaredPluginsLoadsNothingAndNeverResolves(@TempDir Path dir) {
        final boolean[] resolverCalled = {false};
        PluginBootstrap bootstrap = new PluginBootstrap(Collections.emptyMap(),
            coordinates -> { resolverCalled[0] = true; return Collections.emptyList(); },
            PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader());

        LoadedPlugins loaded = bootstrap.load();

        assertTrue(loaded.isEmpty());
        assertFalse(resolverCalled[0], "the jar resolver must not run when no plugins are declared");
        assertFalse(bootstrap.hasPlugins());
    }

    @Test
    void declaredCoordinatesAreMappedAndLoaded(@TempDir Path dir) throws Exception {
        String src = "package p; public class Boot implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTaskType(\"kt\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:kt\"); } }";
        File jar = new PluginJarBuilder(dir).build("p.Boot", src, descriptor("io.example.kt", "p.Boot")).jar;

        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("io.example.forge.kotlin", "1.2.0");

        List<String> asked = new ArrayList<>();
        PluginBootstrap bootstrap = new PluginBootstrap(plugins,
            coordinates -> { asked.addAll(coordinates); return Collections.singletonList(jar); },
            PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader());

        assertTrue(bootstrap.hasPlugins());
        assertEquals(Collections.singletonList("io.example.forge.kotlin:1.2.0"), bootstrap.coordinates());

        LoadedPlugins loaded = bootstrap.load();

        assertEquals(Collections.singletonList("io.example.forge.kotlin:1.2.0"), asked);
        assertEquals(Collections.singletonList("io.example.kt:1.0.0"), loaded.idVersions());
        assertTrue(loaded.registry().taskTypes().contains("kt"));
    }

    @Test
    void coordinatesAreSortedForAStableOrder(@TempDir Path dir) {
        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("io.example.z", "1.0.0");
        plugins.put("io.example.a", "2.0.0");

        PluginBootstrap bootstrap = new PluginBootstrap(plugins,
            coordinates -> Collections.emptyList(),
            PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader());

        assertEquals(Arrays.asList("io.example.a:2.0.0", "io.example.z:1.0.0"),
            bootstrap.coordinates());
    }
}
