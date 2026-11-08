package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Resolves plugin jars through the same Maven Resolver engine and the same
 * local cache ({@code ~/.forge/cache/deps/}) as project dependencies (AGENT.md
 * sections 2 and 4).
 */
public final class AetherPluginJarResolver implements PluginJarResolver {

    private final Map<String, String> repositories;

    public AetherPluginJarResolver(Map<String, String> repositories) {
        this.repositories = repositories == null ? Collections.emptyMap() : repositories;
    }

    @Override
    public List<File> resolve(List<String> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return Collections.emptyList();
        }
        File localCacheDir = Paths.get(System.getProperty("user.home"),
            ".forge", "cache", "deps").toFile();
        AetherDependencyResolver aether =
            new AetherDependencyResolver(localCacheDir, repositories);
        List<File> jars = new ArrayList<>();
        for (String coordinate : coordinates) {
            try {
                jars.add(aether.resolveArtifactFile(coordinate));
            } catch (AetherDependencyResolver.ResolutionFailedException e) {
                throw new ForgeException(ErrorCode.PLG_0008,
                    "cannot resolve plugin '" + coordinate + "': " + e.getMessage(), e);
            }
        }
        return jars;
    }
}
