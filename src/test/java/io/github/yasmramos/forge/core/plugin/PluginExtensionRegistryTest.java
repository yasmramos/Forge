package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ForgeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the plugin extension registry rules (AGENT.md section 4). */
class PluginExtensionRegistryTest {

    @Test
    void builtInTaskTypesCannotBeReplaced() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        for (String builtIn : PluginExtensionRegistry.BUILT_IN_TASK_TYPES) {
            ForgeException e = assertThrows(ForgeException.class,
                () -> registry.registerTaskType("io.example.p", builtIn));
            assertEquals("FORGE-PLG-0007", e.getCode());
        }
    }

    @Test
    void newTaskTypesAreAccepted() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        registry.registerTaskType("io.example.kotlin", "kotlinCompile");
        assertTrue(registry.taskTypes().contains("kotlinCompile"));
        assertEquals(java.util.Collections.singleton("task-type:kotlinCompile"),
            registry.extensionPointsOf("io.example.kotlin"));
    }

    @Test
    void twoOwnersOfTheSameExtensionPointFailWithPlg0006() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        registry.registerCompiler("io.example.a", "kt");
        ForgeException e = assertThrows(ForgeException.class,
            () -> registry.registerCompiler("io.example.b", "kt"));
        assertEquals("FORGE-PLG-0006", e.getCode());
    }

    @Test
    void extensionPointsAggregateEveryKind() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        registry.registerTaskType("io.example.p", "kotlinCompile");
        registry.registerCompiler("io.example.p", "kotlin");
        registry.registerTestRunner("io.example.p", "kotest");
        registry.registerPackagingType("io.example.p", "war");
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "task-type:kotlinCompile", "compiler:kotlin",
            "test-runner:kotest", "packaging-type:war")),
            registry.extensionPoints());
    }

    @Test
    void taskTypesWithFactoriesTracksWhichCanContributeTasks() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        registry.registerTaskType("io.example.p", "listedOnly");
        registry.registerTaskType("io.example.p", "withFactory",
            project -> java.util.Collections.emptyList());

        assertTrue(registry.taskTypes().contains("listedOnly"));
        assertTrue(registry.taskTypes().contains("withFactory"));
        assertEquals(java.util.Collections.singleton("withFactory"),
            registry.taskTypesWithFactories());
        assertNotNull(registry.taskFactory("withFactory"));
        assertNull(registry.taskFactory("listedOnly"));
    }

    @Test
    void compilerLanguagesTrackWhichCanCompile() {
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        registry.registerCompiler("io.example.p", "listedOnly");
        io.github.yasmramos.forge.plugin.PluginCompiler impl =
            new io.github.yasmramos.forge.plugin.PluginCompiler() {
                public String sourceExtension() { return "kt"; }
                public io.github.yasmramos.forge.plugin.PluginCompilationResult compile(
                    io.github.yasmramos.forge.plugin.PluginCompilationRequest r) {
                    return io.github.yasmramos.forge.plugin.PluginCompilationResult.success();
                }
            };
        registry.registerCompiler("io.example.p", "kotlin", impl);

        // Both are listed as extension points; only one can actually compile.
        assertTrue(registry.compilers().contains("listedOnly"));
        assertTrue(registry.compilers().contains("kotlin"));
        assertEquals(java.util.Collections.singleton("kotlin"), registry.compilerLanguages());
        assertSame(impl, registry.compiler("kotlin"));
        assertNull(registry.compiler("listedOnly"));
    }
}
