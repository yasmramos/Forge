package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.ForgePlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires the {@code [plugins]} declared in {@code forge.toml} to the loader
 * (AGENT.md sections 4 and 4bis): each entry {@code "group:artifact" = "version"}
 * becomes the Maven coordinate whose jar is resolved and loaded. This is where
 * configuration meets bytecode; the loader itself knows nothing about
 * {@code forge.toml}.
 *
 * <p>Loading is lazy: it happens only when {@link #load()} is called, so a
 * project that declares no plugins never touches the resolver.</p>
 */
public final class PluginBootstrap {

    private final Map<String, Object> declaredPlugins;
    private final PluginJarResolver jarResolver;
    private final PluginDependencyResolver dependencyResolver;
    private final ClassLoader apiParent;

    public PluginBootstrap(Map<String, Object> declaredPlugins,
                           PluginJarResolver jarResolver,
                           PluginDependencyResolver dependencyResolver,
                           ClassLoader apiParent) {
        this.declaredPlugins = declaredPlugins == null
            ? java.util.Collections.emptyMap() : declaredPlugins;
        this.jarResolver = jarResolver;
        this.dependencyResolver = dependencyResolver;
        this.apiParent = apiParent;
    }

    /** Builds a bootstrap for a project, using Aether-backed resolvers. */
    public static PluginBootstrap forProject(ProjectConfig config) {
        return new PluginBootstrap(config.getPlugins(),
            new AetherPluginJarResolver(config.getRepositories()),
            new AetherPluginDependencyResolver(config.getRepositories()),
            ForgePlugin.class.getClassLoader());
    }

    /** True when the project declares no plugins: nothing to resolve or load. */
    public boolean hasPlugins() {
        return !declaredPlugins.isEmpty();
    }

    /**
     * The plugin coordinates implied by the configuration, as
     * {@code group:artifact:version}, sorted for a stable order.
     */
    public List<String> coordinates() {
        List<String> coordinates = new ArrayList<>();
        for (Map.Entry<String, Object> e : declaredPlugins.entrySet()) {
            coordinates.add(e.getKey() + ":" + e.getValue());
        }
        java.util.Collections.sort(coordinates);
        return coordinates;
    }

    /** Resolves and loads every declared plugin, isolated. */
    public LoadedPlugins load() {
        if (!hasPlugins()) {
            return new LoadedPlugins(new PluginCatalog(), new PluginExtensionRegistry(),
                java.util.Collections.emptyList());
        }
        List<File> jars = jarResolver.resolve(coordinates());
        return new PluginLoader(dependencyResolver, apiParent).load(jars);
    }
}
