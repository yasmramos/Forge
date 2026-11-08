package io.github.yasmramos.forge.core.plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves a plugin's own dependencies to jars on disk (AGENT.md section 4:
 * "a plugin declares its dependencies in its forge-plugin.toml, and Forge
 * resolves them for it, isolated"). Kept as an interface so the loader can be
 * tested without touching the network; the Aether-backed implementation is
 * {@link AetherPluginDependencyResolver}.
 */
public interface PluginDependencyResolver {

    /**
     * Resolves the given Maven coordinates (for example
     * {@code org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.22}) to their
     * jar files, transitively. Never returns the plugin's own jar; the loader
     * adds that separately.
     *
     * @throws io.github.yasmramos.forge.cli.error.ForgeException FORGE-PLG-0008
     *         when a coordinate cannot be resolved
     */
    List<Path> resolve(List<String> coordinates);
}
