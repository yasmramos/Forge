package io.github.yasmramos.forge.core.task;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that the task cache key covers the build environment (AGENT.md
 * section 3): toolchain, Forge version and plugins. A change to any of them
 * must invalidate cached outputs, or a build under a different JDK/core would
 * serve stale bytecode as a hit.
 */
class BuildEnvironmentTest {

    @Test
    void toolchainChangeInvalidatesTaskKey() {
        ForgeTask jdk11 = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("11.0.2", "1.0.0-SNAPSHOT", Collections.emptyList()));
        ForgeTask jdk17 = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT", Collections.emptyList()));

        assertNotEquals(jdk11.computeHash(), jdk17.computeHash(),
            "compiling with a different JDK must produce a different key");
    }

    @Test
    void forgeVersionChangeInvalidatesTaskKey() {
        ForgeTask v1 = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT", Collections.emptyList()));
        ForgeTask v2 = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.1.0", Collections.emptyList()));

        assertNotEquals(v1.computeHash(), v2.computeHash(),
            "upgrading Forge must produce a different key");
    }

    @Test
    void pluginVersionChangeInvalidatesTaskKey() {
        ForgeTask before = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT",
                Collections.singletonList("io.example.forge.kotlin:1.2.0")));
        ForgeTask after = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT",
                Collections.singletonList("io.example.forge.kotlin:1.9.0")));

        assertNotEquals(before.computeHash(), after.computeHash(),
            "bumping a plugin that affects the task must produce a different key");
    }

    @Test
    void pluginOrderDoesNotAffectKey() {
        ForgeTask a = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT",
                Arrays.asList("a:1", "b:2")));
        ForgeTask b = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT",
                Arrays.asList("b:2", "a:1")));

        assertEquals(a.computeHash(), b.computeHash(),
            "plugin declaration order must not affect the key");
    }

    @Test
    void sameEnvironmentIsStableAcrossCalls() {
        ForgeTask t = new ForgeTask("compile", new TaskTypeRegistry().get(TaskType.COMPILE))
            .environment(env("17.0.9", "1.0.0-SNAPSHOT", Collections.emptyList()));

        assertEquals(t.computeHash(), t.computeHash(),
            "the same environment must hash deterministically");
    }

    @Test
    void toolchainHashIsNotTheBareVersionString() {
        String h = BuildEnvironment.toolchainHash("OpenJDK 64-Bit Server VM", "17.0.9", "Eclipse Adoptium");
        assertTrue(h.matches("[0-9a-f]{64}"), "toolchain hash must be a SHA-256 hex digest");
        assertNotEquals(
            BuildEnvironment.toolchainHash("OpenJDK 64-Bit Server VM", "17.0.9", "Eclipse Adoptium"),
            BuildEnvironment.toolchainHash("OpenJDK 64-Bit Server VM", "17.0.9", "Azul"),
            "the vendor is part of the toolchain identity");
    }

    @Test
    void forgeVersionFallsBackDeterministically() {
        // A classpath without pom.properties still hashes to something stable,
        // so caching stays correct (just coarser) instead of throwing.
        assertEquals(BuildEnvironment.forgeVersionHash(), BuildEnvironment.forgeVersionHash());
        assertTrue(BuildEnvironment.forgeVersionHash().matches("[0-9a-f]{64}"));
    }

    private static BuildEnvironment env(String jdkVersion, String forgeVersion, java.util.List<String> plugins) {
        return new BuildEnvironment(
            BuildEnvironment.toolchainHash("OpenJDK 64-Bit Server VM", jdkVersion, "Eclipse Adoptium"),
            BuildEnvironment.forgeVersionHash(forgeVersion),
            BuildEnvironment.pluginsHash(plugins));
    }
}
