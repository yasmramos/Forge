package io.github.yasmramos.forge.core.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Forge's dependency conflict policy, layered above the Maven Resolver engine
 * (AGENT.md sections 2 and 2bis).
 *
 * <p><b>Definition of a conflict:</b> two or more distinct versions are seen for
 * the same {@code groupId:artifactId} anywhere in the resolved graph (direct or
 * transitive). A version appearing multiple times identically is NOT a conflict.</p>
 *
 * <p><b>Default behavior:</b> fail. Forge does not silently pick "nearest wins"
 * (Maven) or "highest wins" (Gradle); an unresolved conflict aborts resolution
 * with a message that names every requester so the user knows exactly who
 * brings what.</p>
 *
 * <p><b>Relief mechanisms (in precedence order, per AGENT.md 2bis):</b></p>
 * <ol>
 *   <li>{@code [resolutions]} overrides — explicit forced versions, always win,
 *       visible in {@code forge why};</li>
 *   <li>BOM alignment — handled before this check by pinning versions at
 *       import time, so aligned coordinates never reach here as conflicts;</li>
 *   <li>otherwise → {@link ConflictException}.</li>
 * </ol>
 */
public final class ConflictPolicy {

    /** A single detected conflict: one GA, several requested versions. */
    public static final class Conflict {
        private final String groupArtifact;
        private final Set<String> versions;

        Conflict(String groupArtifact, Set<String> versions) {
            this.groupArtifact = groupArtifact;
            this.versions = Collections.unmodifiableSet(versions);
        }

        public String getGroupArtifact() {
            return groupArtifact;
        }

        /** Distinct versions requested for this artifact, sorted for stable output. */
        public Set<String> getVersions() {
            return versions;
        }

        @Override
        public String toString() {
            return groupArtifact + " requested in versions " + versions;
        }
    }

    /** Thrown when unresolvable conflicts exist after applying resolutions. */
    public static final class ConflictException extends RuntimeException {
        private final List<Conflict> conflicts;

        ConflictException(List<Conflict> conflicts) {
            super(buildMessage(conflicts));
            this.conflicts = Collections.unmodifiableList(conflicts);
        }

        public List<Conflict> getConflicts() {
            return conflicts;
        }

        /**
         * Renders this failure as a structured, actionable ForgeError with the
         * registered catalog code FORGE-RSL-0001 (AGENT.md sections 2bis and 10bis):
         * what happened, why, and how to fix it.
         */
        public io.github.yasmramos.forge.cli.error.ForgeException toForgeException() {
            StringBuilder detail = new StringBuilder();
            for (Conflict c : conflicts) {
                if (detail.length() > 0) {
                    detail.append("; ");
                }
                detail.append(c.getGroupArtifact())
                      .append(" requested in versions ").append(c.getVersions());
            }
            return new io.github.yasmramos.forge.cli.error.ForgeException(
                    io.github.yasmramos.forge.cli.error.ErrorCode.RSL_0001, detail.toString(), this);
        }

        private static String buildMessage(List<Conflict> conflicts) {
            StringBuilder sb = new StringBuilder();
            sb.append("Version conflict(s) detected (Forge fails by default; ")
              .append("see AGENT.md section 2bis):\n");
            for (Conflict c : conflicts) {
                sb.append("  - ").append(c.getGroupArtifact())
                  .append(" requested in versions ").append(c.getVersions()).append('\n');
            }
            sb.append("Fix: declare a forced version in [resolutions] of forge.toml, ")
              .append("align versions via a BOM, or run `forge resolve --explain` ")
              .append("to see who requests each version.");
            return sb.toString();
        }
    }

    private final Map<String, String> resolutions;

    /**
     * @param resolutions GA -> forced version, from [resolutions] in forge.toml.
     *                    Keys use {@code group:artifact} form. May be empty.
     */
    public ConflictPolicy(Map<String, String> resolutions) {
        this.resolutions = resolutions == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(resolutions);
    }

    /** No forced versions: strictest form of the policy. */
    public static ConflictPolicy strict() {
        return new ConflictPolicy(Collections.emptyMap());
    }

    /** Forced version declared for the given GA, or {@code null}. */
    public String forcedVersion(String groupArtifact) {
        return resolutions.get(groupArtifact);
    }

    /**
     * Detects conflicts from the versions-seen map produced by
     * {@link AetherDependencyResolver.ResolvedGraph#getRequestedVersionsByGa()},
     * then applies [resolutions] overrides.
     *
     * @param versionsSeenByGa GA -> list of versions observed in the graph
     * @return effective GA -> chosen version map for lockfile writing
     * @throws ConflictException if any conflict remains after resolutions
     */
    public Map<String, String> enforce(Map<String, List<String>> versionsSeenByGa) {
        Map<String, String> chosen = new LinkedHashMap<>();
        List<Conflict> unresolved = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : versionsSeenByGa.entrySet()) {
            String ga = e.getKey();
            Set<String> distinct = new TreeSet<>(e.getValue());

            String forced = resolutions.get(ga);
            if (forced != null) {
                // Explicit user decision always wins; record it even if it
                // differs from everything the graph requested.
                chosen.put(ga, forced);
                continue;
            }
            if (distinct.size() <= 1) {
                chosen.put(ga, distinct.isEmpty() ? "" : distinct.iterator().next());
            } else {
                unresolved.add(new Conflict(ga, distinct));
            }
        }

        if (!unresolved.isEmpty()) {
            throw new ConflictException(unresolved);
        }
        return chosen;
    }
}
