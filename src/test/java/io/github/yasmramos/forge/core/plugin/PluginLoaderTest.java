package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.plugin.ForgePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the plugin loader: it compiles a real plugin jar in the
 * test (offline), loads it through an isolated classloader, and checks the
 * registration and consistency rules of AGENT.md section 4/4bis.
 */
class PluginLoaderTest {

    private PluginLoader loader() {
        return new PluginLoader(PluginJarBuilder.noDependencies(),
            ForgePlugin.class.getClassLoader());
    }

    private static String descriptor(String id, String main, String api, String... provides) {
        StringBuilder sb = new StringBuilder();
        sb.append("schema = \"0.1\"\n[plugin]\n")
          .append("id = \"").append(id).append("\"\n")
          .append("name = \"").append(id).append("\"\n")
          .append("version = \"1.0.0\"\n")
          .append("api = \"").append(api).append("\"\n")
          .append("main = \"").append(main).append("\"\n");
        for (int i = 0; i < provides.length; i += 2) {
            sb.append("[[provides]]\ntype = \"").append(provides[i])
              .append("\"\nname = \"").append(provides[i + 1]).append("\"\n");
        }
        return sb.toString();
    }

    @Test
    void loadsAPluginAndRegistersItsTaskType(@TempDir Path dir) throws Exception {
        String src = "package p; public class Good implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTaskType(\"kotlinCompile\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"task-type:kotlinCompile\"); } }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.Good", src,
            descriptor("io.example.kotlin", "p.Good", ">=0.1 <1.0", "task-type", "kotlinCompile"));

        LoadedPlugins loaded = loader().load(Collections.singletonList(built.jar));

        assertEquals(1, loaded.catalog().size());
        assertEquals(Collections.singletonList("io.example.kotlin:1.0.0"), loaded.idVersions());
        assertTrue(loaded.registry().taskTypes().contains("kotlinCompile"));
        assertEquals(1, loaded.classLoaders().size());
        assertEquals("io.example.kotlin", loaded.classLoaders().get(0).getPluginId());
    }

    @Test
    void incompatibleApiIsRejectedBeforeLoading(@TempDir Path dir) throws Exception {
        String src = "package p; public class Future implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){} }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.Future", src,
            descriptor("io.example.future", "p.Future", ">=9.0 <10.0"));

        ForgeException e = PluginJarBuilder.loadExpectingFailure(loader(), built.jar);
        assertEquals("FORGE-PLG-0001", e.getCode());
    }

    @Test
    void missingEntryPointFailsWithPlg0009(@TempDir Path dir) throws Exception {
        String src = "package p; public class Other implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){} }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.Other", src,
            descriptor("io.example.missing", "p.NotThere", ">=0.1 <1.0"));

        assertEquals("FORGE-PLG-0009",
            PluginJarBuilder.loadExpectingFailure(loader(), built.jar).getCode());
    }

    @Test
    void classNotImplementingForgePluginFailsWithPlg0009(@TempDir Path dir) throws Exception {
        String src = "package p; public class NotAPlugin { public NotAPlugin(){} }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.NotAPlugin", src,
            descriptor("io.example.bad", "p.NotAPlugin", ">=0.1 <1.0"));

        assertEquals("FORGE-PLG-0009",
            PluginJarBuilder.loadExpectingFailure(loader(), built.jar).getCode());
    }

    @Test
    void providesDriftFailsWithPlg0005(@TempDir Path dir) throws Exception {
        // Declares nothing, but registers a task type at runtime.
        String src = "package p; public class Drift implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTaskType(\"x\"); } }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.Drift", src,
            descriptor("io.example.drift", "p.Drift", ">=0.1 <1.0"));

        assertEquals("FORGE-PLG-0005",
            PluginJarBuilder.loadExpectingFailure(loader(), built.jar).getCode());
    }

    @Test
    void replacingABuiltInTaskTypeFailsWithPlg0007(@TempDir Path dir) throws Exception {
        String src = "package p; public class Shadow implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerTaskType(\"compile\"); } }";
        PluginJarBuilder.Built built = new PluginJarBuilder(dir).build("p.Shadow", src,
            descriptor("io.example.shadow", "p.Shadow", ">=0.1 <1.0", "task-type", "compile"));

        assertEquals("FORGE-PLG-0007",
            PluginJarBuilder.loadExpectingFailure(loader(), built.jar).getCode());
    }

    @Test
    void duplicatePluginIdAcrossJarsFailsWithPlg0004(@TempDir Path dir) throws Exception {
        String src1 = "package p; public class A implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){} }";
        String src2 = "package p; public class B implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){} }";
        PluginJarBuilder b = new PluginJarBuilder(dir);
        File jar1 = b.build("p.A", src1, descriptor("io.example.dup", "p.A", ">=0.1 <1.0")).jar;
        File jar2 = b.build("p.B", src2, descriptor("io.example.dup", "p.B", ">=0.1 <1.0")).jar;

        // sanity: a single load succeeds
        assertEquals(1, loader().load(Collections.singletonList(jar1)).catalog().size());

        try {
            loader().load(Arrays.asList(jar1, jar2));
            fail("expected FORGE-PLG-0004");
        } catch (ForgeException ex) {
            assertEquals("FORGE-PLG-0004", ex.getCode());
        }
    }

    @Test
    void twoPluginsRegisteringTheSameExtensionPointFailsWithPlg0006(@TempDir Path dir) throws Exception {
        String src1 = "package p; public class C implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerCompiler(\"kt\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"compiler:kt\"); } }";
        String src2 = "package p; public class D implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){ c.registerCompiler(\"kt\"); }"
            + " public java.util.Set<String> provides(){ return java.util.Collections.singleton(\"compiler:kt\"); } }";
        PluginJarBuilder b = new PluginJarBuilder(dir);
        File jar1 = b.build("p.C", src1, descriptor("io.example.c1", "p.C", ">=0.1 <1.0", "compiler", "kt")).jar;
        File jar2 = b.build("p.D", src2, descriptor("io.example.c2", "p.D", ">=0.1 <1.0", "compiler", "kt")).jar;

        try {
            loader().load(Arrays.asList(jar1, jar2));
            fail("expected FORGE-PLG-0006");
        } catch (ForgeException e) {
            assertEquals("FORGE-PLG-0006", e.getCode());
        }
    }

    @Test
    void pluginDependenciesAreResolvedAndPutOnTheIsolatedClasspath(@TempDir Path dir) throws Exception {
        // The resolver is called with the declared coordinate and its jars land
        // on the plugin classpath; a no-op stub proves the wiring without a network.
        final java.util.List<String> asked = new java.util.ArrayList<>();
        Path dep = Files.createTempFile(dir, "dep-", ".jar");
        PluginDependencyResolver resolver = coordinates -> {
            asked.addAll(coordinates);
            return Collections.singletonList(dep);
        };
        String src = "package p; public class WithDep implements io.github.yasmramos.forge.plugin.ForgePlugin {"
            + " public void onLoad(io.github.yasmramos.forge.plugin.PluginContext c){} }";
        String toml = descriptor("io.example.dep", "p.WithDep", ">=0.1 <1.0")
            + "[dependencies]\n\"org.example:lib\" = \"2.0\"\n";
        File jar = new PluginJarBuilder(dir).build("p.WithDep", src, toml).jar;

        new PluginLoader(resolver, ForgePlugin.class.getClassLoader())
            .load(Collections.singletonList(jar));

        assertEquals(Collections.singletonList("org.example:lib:2.0"), asked);
    }
}
