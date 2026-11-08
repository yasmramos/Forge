package io.github.yasmramos.forge.core.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders the version derivation of a resolved graph (AGENT.md section 2bis):
 * which artifacts conflict, who requests each version, and which relief
 * mechanism would settle it.
 *
 * <p>{@code forge resolve --explain} prints this in full; the FORGE-DEP-0001
 * error embeds {@link #condense} so a failed resolution already carries the
 * guidance instead of being pure friction.</p>
 */
public final class ConflictDerivation {

    private ConflictDerivation() {
    }

    /** The GAs seen with more than one distinct version, sorted for stable output. */
    public static List<String> conflictedGAs(Map<String, List<String>> versionsByGa) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : versionsByGa.entrySet()) {
            if (new TreeSet<>(e.getValue()).size() > 1) {
                out.add(e.getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * One line per conflict: the versions requested, who requests each, and the
     * lever (if any) that pins it. Empty when there is no conflict.
     */
    public static String condense(DependencyTree tree,
                                  Map<String, List<String>> versionsByGa,
                                  ConflictPolicy policy) {
        StringBuilder sb = new StringBuilder();
        for (String ga : conflictedGAs(versionsByGa)) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            TreeSet<String> versions = new TreeSet<>(versionsByGa.get(ga));
            sb.append(ga).append(" requested in ").append(versions);
            List<String> requesters = new ArrayList<>();
            for (String v : versions) {
                requesters.add(v + " by " + describeRequesters(tree, ga, v));
            }
            sb.append(" (").append(String.join("; ", requesters)).append(")");
            String lever = policy.lever(ga);
            if (lever != null) {
                sb.append("; ").append(lever).append(" pins ").append(policy.effectiveForced(ga));
            }
        }
        return sb.toString();
    }

    /**
     * The parents that request {@code ga} at exactly {@code version}. Falls back
     * to {@code "(direct)"} when the artifact is requested by the project root or
     * the path is too short to name a parent.
     */
    public static String describeRequesters(DependencyTree tree, String ga, String version) {
        List<String> parents = new ArrayList<>();
        for (List<DependencyTree.Node> path : tree.pathsTo(ga)) {
            DependencyTree.Node leaf = path.get(path.size() - 1);
            if (!version.equals(leaf.getVersion())) {
                continue;
            }
            String parent = path.size() >= 2
                ? path.get(path.size() - 2).getCoordinate()
                : "(direct)";
            if (!parents.contains(parent)) {
                parents.add(parent);
            }
        }
        return parents.isEmpty() ? "(direct)" : String.join(", ", parents);
    }
}
