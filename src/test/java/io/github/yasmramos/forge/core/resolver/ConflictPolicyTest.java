package io.github.yasmramos.forge.core.resolver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Forge conflict policy (AGENT.md sections 2 and 2bis).
 * Pure logic: no network, no repository access.
 */
class ConflictPolicyTest {

    private static Map<String, List<String>> versions(Object... gaAndVersions) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < gaAndVersions.length; i += 2) {
            String ga = (String) gaAndVersions[i];
            @SuppressWarnings("unchecked")
            List<String> vs = (List<String>) gaAndVersions[i + 1];
            m.put(ga, vs);
        }
        return m;
    }

    @Test
    void identicalVersionsAreNotAConflict() {
        ConflictPolicy policy = ConflictPolicy.strict();
        Map<String, List<String>> seen = versions(
                "org.slf4j:slf4j-api", Arrays.asList("2.0.7", "2.0.7"));
        Map<String, String> chosen = policy.enforce(seen);
        assertEquals(Collections.singletonMap("org.slf4j:slf4j-api", "2.0.7"), chosen);
    }

    @Test
    void distinctVersionsFailByDefault() {
        ConflictPolicy policy = ConflictPolicy.strict();
        Map<String, List<String>> seen = versions(
                "com.google.guava:guava", Arrays.asList("31.1-jre", "32.1.3-jre"));

        ConflictPolicy.ConflictException ex = assertThrows(
                ConflictPolicy.ConflictException.class, () -> policy.enforce(seen));

        assertEquals(1, ex.getConflicts().size());
        ConflictPolicy.Conflict c = ex.getConflicts().get(0);
        assertEquals("com.google.guava:guava", c.getGroupArtifact());
        // Versions sorted for stable output.
        assertTrue(c.getVersions().containsAll(Arrays.asList("31.1-jre", "32.1.3-jre")));
        // Actionable message: what happened, who conflicts, how to fix (AGENT.md 10bis).
        String msg = ex.getMessage();
        assertTrue(msg.contains("Version conflict"), "message names the problem");
        assertTrue(msg.contains("[resolutions]"), "message points at a relief mechanism");
        assertTrue(msg.contains("forge resolve --explain"), "message offers diagnosis tool");
    }

    @Test
    void resolutionsOverrideWinsOverGraph() {
        Map<String, String> forced = new LinkedHashMap<>();
        forced.put("com.google.guava:guava", "33.0.0-jre");
        ConflictPolicy policy = new ConflictPolicy(forced);

        Map<String, List<String>> seen = versions(
                "com.google.guava:guava", Arrays.asList("31.1-jre", "32.1.3-jre"),
                "org.slf4j:slf4j-api", Collections.singletonList("2.0.7"));

        Map<String, String> chosen = policy.enforce(seen);
        // Forced version wins even though NOBODY in the graph requested it.
        assertEquals("33.0.0-jre", chosen.get("com.google.guava:guava"));
        assertEquals("2.0.7", chosen.get("org.slf4j:slf4j-api"));
        assertEquals("33.0.0-jre", policy.forcedVersion("com.google.guava:guava"));
    }

    @Test
    void multipleConflictsReportedTogether() {
        ConflictPolicy policy = ConflictPolicy.strict();
        Map<String, List<String>> seen = versions(
                "a:a", Arrays.asList("1", "2"),
                "b:b", Arrays.asList("3", "4"),
                "c:c", Collections.singletonList("5"));

        ConflictPolicy.ConflictException ex = assertThrows(
                ConflictPolicy.ConflictException.class, () -> policy.enforce(seen));
        assertEquals(2, ex.getConflicts().size());
        assertFalse(ex.getMessage().isEmpty());
    }

    @Test
    void nullResolutionsMapBehavesAsStrict() {
        ConflictPolicy policy = new ConflictPolicy(null);
        Map<String, String> chosen = policy.enforce(
                versions("x:x", Collections.singletonList("1.0")));
        assertEquals("1.0", chosen.get("x:x"));
    }
}
