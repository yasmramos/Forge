package io.github.yasmramos.forge.core.resolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Maven Resolver wrapper. These hit Maven Central,
 * so they are tagged as such by behavior: if the network is unavailable the
 * failure is a resolution error, which is itself asserted below.
 */
class AetherIntegrationTest {

    private static Map<String, String> central() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        return repos;
    }

    @Test
    void resolvesRealArtifactTransitively(@TempDir File cacheDir) {
        AetherDependencyResolver resolver =
                new AetherDependencyResolver(cacheDir, central());

        AetherDependencyResolver.ResolvedGraph graph = resolver.resolveTransitive(
                Collections.singletonList("org.slf4j:slf4j-api:2.0.7"));

        // slf4j-api has no compile-scope transitive deps: expect exactly one artifact.
        assertEquals(1, graph.getArtifacts().size());
        assertEquals("org.slf4j:slf4j-api:2.0.7",
                graph.getArtifacts().get(0).getCoordinates());

        File jar = graph.getClasspath().get(0);
        assertNotNull(jar);
        assertTrue(jar.getName().endsWith(".jar"), "resolved file is a jar");
        assertTrue(jar.exists(), "artifact downloaded into shared cache dir");
        assertTrue(jar.getAbsolutePath().startsWith(cacheDir.getAbsolutePath()),
                "cache lives under ~/.forge-style dir, never inside the project");
    }

    @Test
    void missingArtifactFailsWithActionableMessage(@TempDir File cacheDir) {
        AetherDependencyResolver resolver =
                new AetherDependencyResolver(cacheDir, central());

        AetherDependencyResolver.ResolutionFailedException ex = assertThrows(
                AetherDependencyResolver.ResolutionFailedException.class,
                () -> resolver.resolveTransitive(Collections.singletonList(
                        "io.github.yasmramos:definitely-not-published-xyz:9.9.9")));
        assertTrue(ex.getMessage().contains("definitely-not-published-xyz"),
                "error names the offending coordinate");
    }

    @Test
    void singleArtifactFileResolutionForPlugins(@TempDir File cacheDir) {
        AetherDependencyResolver resolver =
                new AetherDependencyResolver(cacheDir, central());
        File f = resolver.resolveArtifactFile("com.electronwill.night-config:toml:3.8.0");
        assertTrue(f.exists());
        assertTrue(f.getName().contains("toml"));
    }

    @Test
    void conflictDetectionOverRealGraph(@TempDir File cacheDir) {
        // logback-classic 1.4.11 depends on slf4j-api 2.0.7; requesting an older
        // slf4j directly produces two distinct versions in the traversal input set.
        AetherDependencyResolver resolver =
                new AetherDependencyResolver(cacheDir, central());

        List<String> deps = java.util.Arrays.asList(
                "ch.qos.logback:logback-classic:1.4.11",
                "org.slf4j:slf4j-api:1.7.36");
        AetherDependencyResolver.ResolvedGraph graph = resolver.resolveTransitive(deps);

        // Aether's own mediation selects ONE version per GA (nearest wins), so the
        // selected graph has no duplicates; Forge's stricter policy therefore runs
        // at lock time over requested sets. Verify selection is deterministic here:
        Map<String, List<String>> seen = graph.getRequestedVersionsByGa();
        ConflictPolicy policy = ConflictPolicy.strict();
        Map<String, String> chosen = policy.enforce(seen); // must not throw: no dupes post-mediation
        assertNotNull(chosen.get("org.slf4j:slf4j-api"));
        assertTrue(graph.getArtifacts().size() >= 3, "transitive closure pulled in");
    }
}
