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
    void conflictIsDetectedOverRealGraphAndLoserStaysOffClasspath(@TempDir File cacheDir) {
        // logback-classic 1.4.11 pulls slf4j-api 2.0.7; requesting 1.7.36 directly
        // makes two distinct versions reachable. This is the case that used to be
        // invisible: without verbose conflict resolution Aether applied Maven's
        // nearest-wins and dropped the loser before Forge could see it, so the
        // fail-by-default policy (AGENT.md section 2bis) never fired.
        AetherDependencyResolver resolver =
                new AetherDependencyResolver(cacheDir, central());

        List<String> deps = java.util.Arrays.asList(
                "ch.qos.logback:logback-classic:1.4.11",
                "org.slf4j:slf4j-api:1.7.36");
        AetherDependencyResolver.ResolvedGraph graph = resolver.resolveTransitive(deps);

        // 1. Both requested versions are now visible to the policy.
        Map<String, List<String>> seen = graph.getRequestedVersionsByGa();
        assertTrue(seen.get("org.slf4j:slf4j-api").containsAll(
                        java.util.Arrays.asList("1.7.36", "2.0.7")),
                "conflict must be visible: both versions were requested");

        // 2. The strict policy therefore fails by default (no silent pick).
        ConflictPolicy.ConflictException ex = assertThrows(
                ConflictPolicy.ConflictException.class,
                () -> ConflictPolicy.strict().enforce(seen));
        assertEquals("org.slf4j:slf4j-api", ex.getConflicts().get(0).getGroupArtifact());

        // 3. The classpath carries exactly ONE slf4j-api jar: the loser never leaks.
        long slf4jApiJars = graph.getArtifacts().stream()
                .filter(a -> a.getCoordinates().startsWith("org.slf4j:slf4j-api:"))
                .count();
        assertEquals(1, slf4jApiJars,
                "only the mediated winner belongs on the classpath");

        // 4. The tree keeps the loser, marked, so `forge why`/`tree` can show it.
        boolean hasOmitted = graph.getTree().flatten().stream()
                .anyMatch(n -> "org.slf4j:slf4j-api".equals(n.getGroupArtifact()) && n.isOmitted());
        assertTrue(hasOmitted, "loser node is retained and flagged in the tree");
    }
}
