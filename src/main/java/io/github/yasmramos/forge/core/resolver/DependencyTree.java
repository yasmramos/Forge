package io.github.yasmramos.forge.core.resolver;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable view of a resolved dependency graph, backing {@code forge tree},
 * {@code forge why} and {@code forge resolve --explain} (AGENT.md section 10).
 *
 * <p>The tree keeps <b>every</b> node Aether traversed, including versions that
 * lost conflict mediation. Losers are marked {@link Node#isOmitted()} and never
 * reach a classpath; they are retained so the conflict policy (section 2bis) and
 * the diagnostic commands can see that two versions of one artifact were
 * requested, instead of the silent nearest-wins pick Maven would otherwise make.</p>
 */
public final class DependencyTree {

    /** One node: a coordinate plus the nodes it pulls in. */
    public static final class Node {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final String scope;
        private final boolean omitted;
        private final String selectedVersion;
        private final List<Node> children;

        Node(String groupId, String artifactId, String version, String scope,
             boolean omitted, String selectedVersion, List<Node> children) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.omitted = omitted;
            this.selectedVersion = selectedVersion;
            this.children = Collections.unmodifiableList(children);
        }

        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public String getScope() { return scope; }

        /** True when this node lost conflict mediation and is not on the classpath. */
        public boolean isOmitted() { return omitted; }

        /** For an omitted node, the version that won; otherwise {@code null}. */
        public String getSelectedVersion() { return selectedVersion; }

        public List<Node> getChildren() { return children; }

        /** {@code null} for the synthetic root node. */
        public String getGroupArtifact() {
            return groupId == null ? null : groupId + ":" + artifactId;
        }

        /** {@code null} for the synthetic root node. */
        public String getCoordinate() {
            return groupId == null ? null : groupId + ":" + artifactId + ":" + version;
        }

        public boolean isRoot() { return groupId == null; }

        /** Matches a {@code group:artifact}, a full coordinate, or a bare artifactId. */
        public boolean matches(String query) {
            if (query == null || groupId == null) {
                return false;
            }
            return query.equals(getGroupArtifact())
                || query.equals(getCoordinate())
                || query.equals(artifactId);
        }

        /** Looser match for {@code forge tree}'s filter: any substring of the coordinate. */
        public boolean matchesFilter(String query) {
            if (query == null || groupId == null) {
                return false;
            }
            return getCoordinate().contains(query) || artifactId.contains(query);
        }
    }

    private final Node root;

    DependencyTree(Node root) {
        this.root = root;
    }

    public Node getRoot() { return root; }

    /** Builds a tree from Aether's resolved root, keeping omitted (loser) nodes. */
    public static DependencyTree from(DependencyNode aetherRoot) {
        return new DependencyTree(convert(aetherRoot));
    }

    private static Node convert(DependencyNode node) {
        Artifact a = node.getArtifact();
        String scope = node.getDependency() != null ? node.getDependency().getScope() : null;
        Object winner = node.getData().get(ConflictResolver.NODE_DATA_WINNER);
        boolean omitted = winner instanceof DependencyNode;
        String selectedVersion = omitted
            ? ((DependencyNode) winner).getArtifact().getVersion()
            : null;

        List<Node> children = new ArrayList<>();
        for (DependencyNode child : node.getChildren()) {
            children.add(convert(child));
        }

        if (a == null) {
            return new Node(null, null, null, scope, false, null, children);
        }
        return new Node(a.getGroupId(), a.getArtifactId(), a.getVersion(), scope,
            omitted, selectedVersion, children);
    }

    /** All paths from the root's children down to every node matching {@code query}. */
    public List<List<Node>> pathsTo(String query) {
        List<List<Node>> out = new ArrayList<>();
        for (Node child : root.getChildren()) {
            collect(child, query, new ArrayList<>(), out);
        }
        return out;
    }

    private static void collect(Node node, String query, List<Node> path, List<List<Node>> out) {
        path.add(node);
        if (node.matches(query)) {
            out.add(new ArrayList<>(path));
        }
        for (Node child : node.getChildren()) {
            collect(child, query, path, out);
        }
        path.remove(path.size() - 1);
    }

    /** Every node in preorder, root excluded. */
    public List<Node> flatten() {
        List<Node> out = new ArrayList<>();
        for (Node child : root.getChildren()) {
            flatten(child, out);
        }
        return out;
    }

    private static void flatten(Node node, List<Node> out) {
        out.add(node);
        for (Node child : node.getChildren()) {
            flatten(child, out);
        }
    }
}
