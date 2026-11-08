package io.github.yasmramos.forge.core.plugin;

import java.util.Collections;
import java.util.List;

/**
 * The result of loading plugins: their descriptors and the extension points
 * they registered (AGENT.md section 4). The classloaders are retained so the
 * loaded classes stay reachable for the life of the build.
 */
public final class LoadedPlugins {

    private final PluginCatalog catalog;
    private final PluginExtensionRegistry registry;
    private final List<PluginClassLoader> classLoaders;

    LoadedPlugins(PluginCatalog catalog, PluginExtensionRegistry registry,
                  List<PluginClassLoader> classLoaders) {
        this.catalog = catalog;
        this.registry = registry;
        this.classLoaders = Collections.unmodifiableList(classLoaders);
    }

    public PluginCatalog catalog() {
        return catalog;
    }

    public PluginExtensionRegistry registry() {
        return registry;
    }

    public List<PluginClassLoader> classLoaders() {
        return classLoaders;
    }

    public boolean isEmpty() {
        return catalog.size() == 0;
    }

    /** Loaded plugins as {@code id:version} pairs, sorted (for cache keys). */
    public List<String> idVersions() {
        return catalog.idVersions();
    }
}
