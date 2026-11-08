package io.github.yasmramos.forge.core.resolver;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the version-derivation renderer backing
 * {@code forge resolve --explain} and the FORGE-DEP-0001 error detail
 * (AGENT.md section 2bis). Pure logic over hand-built Aether nodes.
 */
class ConflictDerivationTest {

    private static DependencyNode node(String coord) {
        return new DefaultDependencyNode(new DefaultArtifact(coord));
    }

    /** root -> a(2.0) -> c(1.0) ; root -> b(3.0) -> c(2.0): c conflicts. */
    private static DependencyTree treeWithConflict() {
        DependencyNode root = new DefaultDependencyNode(new DefaultArtifact("x:y:1"));
        DependencyNode a = node("com.foo:a:2.0");
        DependencyNode b = node("com.foo:b:3.0");
        DependencyNode c1 = node("com.foo:c:1.0");
        DependencyNode c2 = node("com.foo:c:2.0");
        a.setChildren(new ArrayList<>(Collections.singletonList(c1)));
        b.setChildren(new ArrayList<>(Collections.singletonList(c2)));
        root.setChildren(new ArrayList<>(Arrays.asList(a, b)));
        return DependencyTree.from(root);
    }

    @Test
    void conflictedGasListOnlyDistinctVersions() {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        seen.put("com.foo:c", Arrays.asList("1.0", "2.0"));
        seen.put("org.slf4j:slf4j-api", Arrays.asList("2.0.7", "2.0.7"));

        List<String> conflicts = ConflictDerivation.conflictedGAs(seen);

        assertEquals(Collections.singletonList("com.foo:c"), conflicts,
            "identical versions are not a conflict (section 2bis)");
    }

    @Test
    void condenseNamesWhoRequestsEachVersion() {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        seen.put("com.foo:c", Arrays.asList("1.0", "2.0"));

        String derivation = ConflictDerivation.condense(
            treeWithConflict(), seen, ConflictPolicy.strict());

        assertTrue(derivation.contains("com.foo:c requested in [1.0, 2.0]"), derivation);
        assertTrue(derivation.contains("1.0 by com.foo:a:2.0"), derivation);
        assertTrue(derivation.contains("2.0 by com.foo:b:3.0"), derivation);
    }

    @Test
    void condenseReportsTheLeverThatWouldSettleIt() {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        seen.put("com.foo:c", Arrays.asList("1.0", "2.0"));
        ConflictPolicy policy = new ConflictPolicy(
            Collections.singletonMap("com.foo:c", "2.0"), Collections.emptyMap());

        String derivation = ConflictDerivation.condense(treeWithConflict(), seen, policy);

        assertTrue(derivation.contains("[resolutions] pins 2.0"), derivation);
    }

    @Test
    void condenseIsEmptyWithoutConflicts() {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        seen.put("com.foo:c", Collections.singletonList("1.0"));

        assertEquals("", ConflictDerivation.condense(
            treeWithConflict(), seen, ConflictPolicy.strict()));
    }

    @Test
    void describeRequestersFallsBackToDirect() {
        DependencyNode root = new DefaultDependencyNode(new DefaultArtifact("x:y:1"));
        root.setChildren(new ArrayList<>(Collections.singletonList(node("com.foo:d:1.0"))));
        DependencyTree tree = DependencyTree.from(root);

        assertEquals("(direct)",
            ConflictDerivation.describeRequesters(tree, "com.foo:d", "1.0"));
    }
}
