package io.github.yasmramos.forge.core.resolver;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.DependencyNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dependency tree model and its renderer, backing
 * {@code forge tree}, {@code forge why} and {@code forge resolve --explain}
 * (AGENT.md sections 10 and 2bis).
 *
 * <p>Pure logic over hand-built Aether nodes: no network, no repository access.
 * The loser-marking rule (a node carrying {@code NODE_DATA_WINNER} is omitted)
 * is the contract the resolver relies on to keep conflicts visible.</p>
 */
class DependencyTreeTest {

    private static DependencyNode node(String coord) {
        return new DefaultDependencyNode(new DefaultArtifact(coord));
    }

    private static void link(DependencyNode parent, DependencyNode child) {
        parent.setChildren(new java.util.ArrayList<>(java.util.Collections.singletonList(child)));
    }

    /** root -> a -> c ; root -> b -> c (c appears twice, same version). */
    private static DependencyTree sampleTree() {
        DependencyNode root = new DefaultDependencyNode(new DefaultArtifact("x:y:1"));
        DependencyNode a = node("com.foo:a:1.0");
        DependencyNode b = node("com.foo:b:1.0");
        DependencyNode c1 = node("com.foo:c:2.0");
        DependencyNode c2 = node("com.foo:c:2.0");
        link(root, a);
        root.setChildren(new java.util.ArrayList<>(Arrays.asList(a, b)));
        link(a, c1);
        link(b, c2);
        return DependencyTree.from(root);
    }

    @Test
    void pathsToReturnsEveryRouteToTheCoordinate() {
        List<List<DependencyTree.Node>> paths = sampleTree().pathsTo("com.foo:c");
        assertEquals(2, paths.size(), "c is reachable through both a and b");
        for (List<DependencyTree.Node> path : paths) {
            assertEquals(2, path.size());
            assertEquals("com.foo:c:2.0", path.get(1).getCoordinate());
        }
    }

    @Test
    void matchesByGaCoordinateOrArtifactId() {
        DependencyTree.Node a = sampleTree().pathsTo("com.foo:a").get(0).get(0);
        assertTrue(a.matches("com.foo:a"));
        assertTrue(a.matches("com.foo:a:1.0"));
        assertTrue(a.matches("a"));
        assertFalse(a.matches("com.foo:z"));
    }

    @Test
    void rendererMarksOmittedNodesWithTheirWinner() {
        DependencyNode root = new DefaultDependencyNode(new DefaultArtifact("x:y:1"));
        DependencyNode winner = node("org.slf4j:slf4j-api:1.7.36");
        DependencyNode loser = node("org.slf4j:slf4j-api:2.0.7");
        // This is how Aether's verbose resolver flags a mediated-away version.
        loser.setData(org.eclipse.aether.util.graph.transformer.ConflictResolver.NODE_DATA_WINNER,
                winner);
        root.setChildren(new java.util.ArrayList<>(Arrays.asList(winner, loser)));

        DependencyTree tree = DependencyTree.from(root);
        String rendered = new DependencyTreeRenderer(0, null).render(tree);

        assertTrue(rendered.contains("org.slf4j:slf4j-api:1.7.36"));
        assertTrue(rendered.contains("omitted for conflict with 1.7.36"),
                "the losing version is shown, with the version that won:\n" + rendered);

        long omitted = tree.flatten().stream().filter(DependencyTree.Node::isOmitted).count();
        assertEquals(1, omitted);
    }

    @Test
    void rendererFilterKeepsOnlyMatchingBranches() {
        DependencyTree tree = sampleTree();
        String rendered = new DependencyTreeRenderer(0, "com.foo:a").render(tree);
        assertTrue(rendered.contains("com.foo:a:1.0"));
        assertFalse(rendered.contains("com.foo:b:1.0"),
                "unrelated branch is pruned by the filter:\n" + rendered);
    }

    @Test
    void rendererFilterMatchesSubstrings() {
        // `forge tree slf4j` should behave like a grep over coordinates.
        String rendered = new DependencyTreeRenderer(0, "com.foo").render(sampleTree());
        assertTrue(rendered.contains("com.foo:a:1.0"));
        assertTrue(rendered.contains("com.foo:c:2.0"));
    }

    @Test
    void rendererRespectsMaxDepth() {
        DependencyTree tree = sampleTree();
        String rendered = new DependencyTreeRenderer(1, null).render(tree);
        assertTrue(rendered.contains("com.foo:a:1.0"));
        assertFalse(rendered.contains("com.foo:c:2.0"),
                "depth 1 stops before the transitive level:\n" + rendered);
    }
}
