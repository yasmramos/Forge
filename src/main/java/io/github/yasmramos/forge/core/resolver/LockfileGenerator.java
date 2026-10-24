package io.github.yasmramos.forge.core.resolver;

import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a resolved dependency graph into a {@link Lockfile} (AGENT.md section 7bis).
 *
 * <p>Pipeline, in the order mandated by AGENT.md:</p>
 * <ol>
 *   <li><b>Resolve transitively</b> with the Maven Resolver engine
 *       ({@link AetherDependencyResolver}) — Forge never reimplements this.</li>
 *   <li><b>Enforce the conflict policy</b> ({@link ConflictPolicy}, section 2bis):
 *       fail by default on version conflicts unless aligned by a BOM or pinned
 *       through [resolutions]. The selected exact versions are returned.</li>
 *   <li><b>Fingerprint every artifact</b> with a mandatory SHA-256 checksum
 *       (section 18: checksums travel with resolution, not as an afterthought).</li>
 *   <li><b>Emit the lock</b> recording all transitive dependencies, their exact
 *       versions, checksums and source repository, plus the config hash of the
 *       forge.toml that produced them.</li>
 * </ol>
 *
 * <p>The lock is only ever written by explicit user action ({@code forge update} /
 * {@code forge lock refresh}); a normal build reads it and fails when stale. That
 * policy lives in the CLI layer, not here.</p>
 */
public final class LockfileGenerator {

    /** Repository name reported for locally-installed artifacts without a remote origin. */
    private static final String LOCAL_REPOSITORY = "local";

    private final File localCacheDir;
    private final AetherDependencyResolver resolver;

    /**
     * @param localCacheDir shared dependency cache; per AGENT.md section 3 this is
     *                      ~/.forge/cache/deps, never inside the project directory
     * @param repositories  name -> base URL from [repositories] in forge.toml
     */
    public LockfileGenerator(File localCacheDir, Map<String, String> repositories) {
        this.localCacheDir = localCacheDir;
        this.resolver = new AetherDependencyResolver(localCacheDir, repositories);
    }

    /**
     * Resolves the compile-scope root set declared in the configuration, applies
     * the strict conflict policy and produces the lock contents (not yet written).
     *
     * @param projectRoot directory containing the forge.toml used for this build
     * @param config      parsed forge.toml configuration
     * @return a Lockfile ready to be {@link Lockfile#write(File) written}
     * @throws ConflictPolicy.ConflictException when unaligned version conflicts exist
     * @throws AetherDependencyResolver.ResolutionFailedException when coordinates cannot be resolved
     */
    public Lockfile generate(File projectRoot, ProjectConfig config) throws Exception {
        List<String> roots = compileScopeCoordinates(config);
        if (roots.isEmpty()) {
            // Nothing to resolve: emit a valid, empty lock so builds stay reproducible.
            return new Lockfile(Lockfile.hashOf(new File(projectRoot, ConfigReader.CONFIG_FILE_NAME)),
                    Collections.emptyMap());
        }

        AetherDependencyResolver.ResolvedGraph graph =
                resolver.resolveTransitive(roots);

        // Section 2bis: fail-by-default policy with [resolutions] overrides.
        Map<String, String> selected =
                new ConflictPolicy(config.getResolutions()).enforce(graph.getRequestedVersionsByGa());

        // Build coordinate -> repository map from the resolved graph paths.
        Map<String, String> repoByCoordinate = new LinkedHashMap<>();
        for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
            repoByCoordinate.put(a.getCoordinates(), repositoryFor(a.getFile()));
        }

        Map<String, Lockfile.LockedDependency> locked = new TreeMap<>();
        for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
            String ga = stripVersion(a.getCoordinates());
            String chosen = selected.get(ga);
            if (chosen == null || !a.getCoordinates().endsWith(":" + chosen)) {
                continue; // superseded by the policy's selected version
            }
            locked.put(ga, new Lockfile.LockedDependency(
                    ga, chosen, sha256Hex(a.getFile()),
                    repoByCoordinate.getOrDefault(a.getCoordinates(), LOCAL_REPOSITORY)));
        }

        String configHash = Lockfile.hashOf(
                new File(projectRoot, ConfigReader.CONFIG_FILE_NAME));
        return new Lockfile(configHash, locked);
    }

    /** Direct compile-scope coordinates ({@code group:artifact:version}) from the config. */
    private static List<String> compileScopeCoordinates(ProjectConfig config) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.getDependencies().entrySet()) {
            Object v = e.getValue();
            String scope = "compile";
            String version = null;
            if (v instanceof Map) {
                Map<?, ?> info = (Map<?, ?>) v;
                if (info.get("scope") != null) {
                    scope = String.valueOf(info.get("scope"));
                }
                if (info.get("version") != null) {
                    version = String.valueOf(info.get("version"));
                }
            } else if (v instanceof String) {
                version = (String) v;
            }
            if ("compile".equals(scope) && version != null) {
                out.add(e.getKey() + ":" + version);
            }
        }
        return out;
    }

    /**
     * Best-effort mapping from a file in the local cache back to the repository
     * name it was fetched from. Artifacts installed locally have no remote origin.
     */
    private String repositoryFor(File artifactFile) {
        // Files under the shared cache were downloaded from the configured remotes;
        // the resolver does not persist provenance metadata in 0.1, so we record
        // the first configured remote (normally "central") as the source.
        try {
            if (artifactFile.getCanonicalPath().startsWith(localCacheDir.getCanonicalPath())) {
                return "central";
            }
        } catch (Exception ignored) {
            // fall through
        }
        return LOCAL_REPOSITORY;
    }

    private static String stripVersion(String coordinates) {
        int i = coordinates.lastIndexOf(':');
        return i < 0 ? coordinates : coordinates.substring(0, i);
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
