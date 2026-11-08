package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Resolves plugin dependencies through the same Maven Resolver engine as the
 * project's own dependencies (AGENT.md sections 2 and 4): no separate code
 * path, no separate cache. Artifacts land in the shared local cache
 * {@code ~/.forge/cache/deps/}, exactly like project dependencies.
 */
public final class AetherPluginDependencyResolver implements PluginDependencyResolver {

    private final Map<String, String> repositories;

    public AetherPluginDependencyResolver(Map<String, String> repositories) {
        this.repositories = repositories == null ? Collections.emptyMap() : repositories;
    }

    @Override
    public List<Path> resolve(List<String> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return Collections.emptyList();
        }
        File localCacheDir = Paths.get(System.getProperty("user.home"),
            ".forge", "cache", "deps").toFile();
        AetherDependencyResolver aether =
            new AetherDependencyResolver(localCacheDir, repositories);
        AetherDependencyResolver.ResolvedGraph graph;
        try {
            graph = aether.resolveTransitive(new ArrayList<>(coordinates));
        } catch (AetherDependencyResolver.ResolutionFailedException e) {
            throw new ForgeException(ErrorCode.PLG_0008, e.getMessage(), e);
        }
        List<Path> jars = new ArrayList<>();
        for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
            if (a.getFile() != null) {
                jars.add(a.getFile().toPath());
            }
        }
        return jars;
    }
}
