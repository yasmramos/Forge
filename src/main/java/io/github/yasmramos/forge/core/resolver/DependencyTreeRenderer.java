package io.github.yasmramos.forge.core.resolver;

import java.util.List;

/**
 * Renders a {@link DependencyTree} as text for {@code forge tree} (AGENT.md
 * section 10: "Dependency tree, with filters").
 *
 * <p>Pure string rendering over an already-resolved tree, so it is testable
 * without touching the network. Omitted (conflict-losing) nodes are shown with
 * the version that won, so the pick is never invisible (section 2bis).</p>
 */
public final class DependencyTreeRenderer {

    private final int maxDepth;
    private final String filter;

    /**
     * @param maxDepth maximum depth to print; {@code <= 0} means unlimited
     * @param filter   when non-null, only branches containing a matching node are
     *                 shown (the match itself plus its ancestors)
     */
    public DependencyTreeRenderer(int maxDepth, String filter) {
        this.maxDepth = maxDepth;
        this.filter = (filter == null || filter.isEmpty()) ? null : filter;
    }

    public String render(DependencyTree tree) {
        StringBuilder sb = new StringBuilder();
        DependencyTree.Node root = tree.getRoot();
        // The synthetic Aether root has no artifact; label it with the project.
        sb.append("(project)").append('\n');
        List<DependencyTree.Node> children = root.getChildren();
        for (int i = 0; i < children.size(); i++) {
            renderNode(children.get(i), "", i == children.size() - 1, 1, sb);
        }
        return sb.toString();
    }

    private void renderNode(DependencyTree.Node node, String prefix, boolean last,
                            int depth, StringBuilder sb) {
        if (!shouldShow(node, depth)) {
            return;
        }
        sb.append(prefix).append(last ? "\\-- " : "+-- ").append(label(node)).append('\n');

        if (maxDepth > 0 && depth >= maxDepth) {
            return;
        }
        List<DependencyTree.Node> children = node.getChildren();
        String childPrefix = prefix + (last ? "    " : "|   ");
        for (int i = 0; i < children.size(); i++) {
            renderNode(children.get(i), childPrefix, i == children.size() - 1, depth + 1, sb);
        }
    }

    private boolean shouldShow(DependencyTree.Node node, int depth) {
        if (filter == null) {
            return true;
        }
        return matchesInSubtree(node, depth);
    }

    private boolean matchesInSubtree(DependencyTree.Node node, int depth) {
        if (node.matchesFilter(filter)) {
            return true;
        }
        if (maxDepth > 0 && depth >= maxDepth) {
            return false;
        }
        for (DependencyTree.Node child : node.getChildren()) {
            if (matchesInSubtree(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static String label(DependencyTree.Node node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getGroupArtifact()).append(':').append(node.getVersion());
        if (node.getScope() != null) {
            sb.append(" (").append(node.getScope()).append(')');
        }
        if (node.isOmitted()) {
            sb.append(" (omitted for conflict with ").append(node.getSelectedVersion()).append(')');
        }
        return sb.toString();
    }
}
