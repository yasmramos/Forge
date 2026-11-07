package io.github.yasmramos.forge.core.resolver;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyType;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapts Maven Resolver (Aether) results to Forge's {@link DependencyResolution}
 * model, applying scope filtering (AGENT.md section 2).
 *
 * <p>This is the single entry point for classpath resolution: the phased engine
 * build and the task-graph compile task both call {@link #resolve}. Keeping one
 * path means dependencies reach a classpath in exactly one way, so the
 * hand-rolled fallback that used to sit behind "no repositories declared"
 * (guessed artifact ids, no checksums) can no longer be reached — it contradicted
 * the "do not reimplement resolution" and "mandatory checksums" decisions
 * (AGENT.md sections 2 and 18).</p>
 *
 * <p>When a project declares dependencies but no {@code [repositories]}, Aether
 * falls back to Maven Central, matching the resolver's own default, instead of
 * guessing coordinates locally.</p>
 */
public final class AetherResolution {

    private AetherResolution() {
    }

    /**
     * Resolves the declared dependencies whose scope is in {@code allowedScopes},
     * plus any {@code extraRoots} (ad-hoc coordinates not declared in
     * {@code forge.toml}, e.g. the JUnit Platform launcher).
     *
     * @param config        parsed project configuration
     * @param allowedScopes scopes to include as resolution roots
     * @param extraRoots    extra {@code group:artifact:version} roots; may be null
     * @return resolved artifacts; empty when there is nothing to resolve
     * @throws ForgeException {@code FORGE-DEP-0002} when a coordinate cannot be
     *                        resolved or downloaded from any repository
     */
    public static DependencyResolution resolve(ProjectConfig config,
                                               Set<String> allowedScopes,
                                               List<String> extraRoots) {
        Map<String, Object> declared = config.getDependencies();
        List<String> roots = new ArrayList<>();
        if (declared != null) {
            for (Map.Entry<String, Object> entry : declared.entrySet()) {
                if (!allowedScopes.contains(DependencyScopes.scopeOf(entry.getValue()))) {
                    continue; // scope not on this classpath
                }
                String version = DependencyScopes.versionOf(entry.getValue());
                if (version != null && !version.trim().isEmpty()) {
                    roots.add(entry.getKey() + ":" + version);
                }
            }
        }
        if (extraRoots != null) {
            for (String extra : extraRoots) {
                String ga = extra.substring(0, extra.lastIndexOf(':'));
                boolean alreadyDeclared = declared != null && declared.containsKey(ga);
                if (!alreadyDeclared && !roots.contains(extra)) {
                    roots.add(extra);
                }
            }
        }
        if (roots.isEmpty()) {
            return new DependencyResolution(); // Nothing to resolve: valid empty graph.
        }

        File localCacheDir = Paths.get(System.getProperty("user.home"),
            ".forge", "cache", "deps").toFile();
        Map<String, String> repositories = config.getRepositories() != null
            ? config.getRepositories() : Collections.emptyMap();
        // Multi-module (AGENT.md section 5): consult the workspace so sibling
        // modules resolve by coordinate to their freshly built artifact.
        org.eclipse.aether.repository.WorkspaceReader workspace = null;
        if (config.getWorkspaceDirectory() != null) {
            workspace = new io.github.yasmramos.forge.core.multimodule.WorkspaceModuleRepository(
                new File(config.getWorkspaceDirectory()), config.getWorkspaceModuleCoordinates());
        }
        AetherDependencyResolver aether =
            new AetherDependencyResolver(localCacheDir, repositories, workspace);

        AetherDependencyResolver.ResolvedGraph graph;
        try {
            graph = aether.resolveTransitive(roots);
        } catch (AetherDependencyResolver.ResolutionFailedException e) {
            throw new ForgeException(ErrorCode.DEP_0002, e.getMessage(), e);
        }

        DependencyResolution resolution = new DependencyResolution();
        for (AetherDependencyResolver.ResolvedArtifact artifact : graph.getArtifacts()) {
            String[] ga = artifact.getCoordinates().split(":");
            if (ga.length != 3) {
                continue;
            }
            resolution.addDependency(new DependencyInfo(
                ga[0] + ":" + ga[1], ga[2], DependencyType.MAVEN,
                artifact.getFile().toPath(), true));
        }
        return resolution;
    }
}
