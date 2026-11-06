package io.github.yasmramos.forge.core.resolver;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the shared Aether-to-model adapter: scope filtering and the
 * "no [repositories] means Central" behavior that replaced the hand-rolled
 * legacy fallback (AGENT.md sections 2 and 18).
 *
 * <p>These hit Maven Central; a network failure surfaces as the same
 * {@code FORGE-DEP-0002} error the adapter maps resolution failures to.</p>
 */
class AetherResolutionTest {

    private static Map<String, Object> dep(String version, String scope) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version", version);
        d.put("scope", scope);
        return d;
    }

    private static ProjectConfig configWith(Map<String, Object> dependencies,
                                            Map<String, String> repositories) {
        ProjectConfig config = new ProjectConfig();
        config.setName("resolution-test");
        config.setVersion("1.0.0");
        config.setDependencies(dependencies);
        config.setRepositories(repositories);
        return config;
    }

    @Test
    void resolvesThroughCentralWhenNoRepositoriesAreDeclared() {
        // A project with a dependency but no [repositories] must still resolve
        // (Aether defaults to Central). The removed legacy path guessed
        // artifact ids and skipped checksums; this asserts the replacement.
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("org.slf4j:slf4j-api", dep("2.0.7", "compile"));

        DependencyResolution resolution = AetherResolution.resolve(
            configWith(deps, Collections.emptyMap()), DependencyScopes.COMPILE, null);

        assertEquals(1, resolution.getSuccessCount());
        assertEquals("org.slf4j:slf4j-api", resolution.getDependencies().get(0).getName());
        assertTrue(resolution.getDependencies().get(0).getLocalFile().getName()
            .endsWith(".jar"), "resolved artifact points at a jar");
    }

    @Test
    void testScopeDependenciesStayOffTheCompileClasspath() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("org.slf4j:slf4j-api", dep("2.0.7", "compile"));
        deps.put("org.junit.jupiter:junit-jupiter", dep("5.9.2", "test"));

        DependencyResolution compile = AetherResolution.resolve(
            configWith(deps, Collections.emptyMap()), DependencyScopes.COMPILE, null);

        assertEquals(1, compile.getSuccessCount(),
            "only the compile-scope dependency belongs on the compile classpath");
        assertEquals("org.slf4j:slf4j-api", compile.getDependencies().get(0).getName());
    }

    @Test
    void unresolvableCoordinateFailsWithDep0002() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("com.example.does.not:exist", dep("9.9.9", "compile"));

        ForgeException ex = assertThrows(ForgeException.class, () ->
            AetherResolution.resolve(configWith(deps, Collections.emptyMap()),
                DependencyScopes.COMPILE, null));
        assertEquals("FORGE-DEP-0002", ex.getCode());
    }

    @Test
    void noDependenciesYieldsEmptyResolution() {
        DependencyResolution resolution = AetherResolution.resolve(
            configWith(new LinkedHashMap<>(), Collections.emptyMap()),
            DependencyScopes.COMPILE, null);
        assertEquals(0, resolution.getSuccessCount());
    }
}
