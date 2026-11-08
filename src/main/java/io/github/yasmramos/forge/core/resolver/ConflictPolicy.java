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
 * transitive). A version appearing multiple times identically is NOT a conflict,
 * and a range is not a conflict by itself: ranges are resolved against
 * candidates before this check.</p>
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
 *   <li>{@code [constraints]} — forced versions, win over everything
 *       transitive but below {@code [resolutions]};</li>
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
        private String derivation;

        ConflictException(List<Conflict> conflicts) {
            super(buildMessage(conflicts));
            this.conflicts = Collections.unmodifiableList(conflicts);
        }

        public List<Conflict> getConflicts() {
            return conflicts;
        }

        /**
         * Attaches a condensed derivation (who requests what, which lever applies)
         * so the structured error can embed it (AGENT.md section 2bis). Returns
         * {@code this} for use in a {@code catch}-and-rethrow.
         */
        public ConflictException withDerivation(String derivation) {
            this.derivation = derivation;
            return this;
        }

        /**
         * Renders this failure as a structured, actionable ForgeError with the
         * registered catalog code FORGE-DEP-0001 (AGENT.md sections 2bis and 10bis):
         * what happened, why, and how to fix it.
         */
        public io.github.yasmramos.forge.cli.error.ForgeException toForgeException() {
            return toForgeException(derivation);
        }

        /**
         * Same as {@link #toForgeException()}, with a condensed derivation
         * (who requests what, which lever applies) embedded in the detail so the
         * error message already guides the fix (AGENT.md section 2bis).
         */
        public io.github.yasmramos.forge.cli.error.ForgeException toForgeException(String derivation) {
            String detail;
            if (derivation != null && !derivation.isEmpty()) {
                // The derivation already names each conflicted GA and who requests
                // what, so it replaces the plain conflict list instead of repeating it.
                detail = derivation;
            } else {
                StringBuilder sb = new StringBuilder();
                for (Conflict c : conflicts) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(c.getGroupArtifact())
                      .append(" requested in versions ").append(c.getVersions());
                }
                detail = sb.toString();
            }
            return new io.github.yasmramos.forge.cli.error.ForgeException(
                    io.github.yasmramos.forge.cli.error.ErrorCode.DEP_0001, detail, this);
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
    private final Map<String, String> constraints;

    /**
     * @param resolutions GA -> forced version, from [resolutions] in forge.toml.
     *                    Keys use {@code group:artifact} form. May be empty.
     */
    public ConflictPolicy(Map<String, String> resolutions) {
        this(resolutions, Collections.emptyMap());
    }

    /**
     * @param resolutions GA -> forced version from [resolutions] (highest precedence)
     * @param constraints GA -> forced version from [constraints] (below resolutions)
     */
    public ConflictPolicy(Map<String, String> resolutions, Map<String, String> constraints) {
        this.resolutions = resolutions == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(resolutions);
        this.constraints = constraints == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(constraints);
    }

    /** No forced versions: strictest form of the policy. */
    public static ConflictPolicy strict() {
        return new ConflictPolicy(Collections.emptyMap());
    }

    /** Forced version declared for the given GA, or {@code null}. */
    public String forcedVersion(String groupArtifact) {
        return effectiveForced(groupArtifact);
    }

    /**
     * The forced version for a GA, applying precedence: {@code [resolutions]}
     * wins over {@code [constraints]}. {@code null} when neither declares it.
     */
    public String effectiveForced(String groupArtifact) {
        String byResolution = resolutions.get(groupArtifact);
        if (byResolution != null) {
            return byResolution;
        }
        return constraints.get(groupArtifact);
    }

    /** Which lever forced this GA's version, for {@code --explain} and {@code why}. */
    public String lever(String groupArtifact) {
        if (resolutions.containsKey(groupArtifact)) {
            return "[resolutions]";
        }
        if (constraints.containsKey(groupArtifact)) {
            return "[constraints]";
        }
        return null;
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

            String forced = effectiveForced(ga);
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
