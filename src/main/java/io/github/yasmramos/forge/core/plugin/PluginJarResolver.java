package io.github.yasmramos.forge.core.plugin;

import java.io.File;
import java.util.List;

/**
 * Resolves a plugin's own jar from its declared Maven coordinate (AGENT.md
 * section 4: "plugins are declared in forge.toml with a Maven coordinate").
 * Kept as an interface so the loader and its bootstrap can be exercised without
 * the network; {@link AetherPluginJarResolver} is the Maven Resolver-backed
 * implementation.
 */
public interface PluginJarResolver {

    /**
     * @param coordinates {@code group:artifact:version} strings
     * @return the plugin jar for each coordinate, in the same order
     * @throws io.github.yasmramos.forge.cli.error.ForgeException FORGE-PLG-0008
     *         when a coordinate cannot be resolved
     */
    List<File> resolve(List<String> coordinates);
}
