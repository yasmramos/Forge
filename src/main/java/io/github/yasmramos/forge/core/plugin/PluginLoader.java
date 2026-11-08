package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.plugin.ForgePlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads plugins from jars with classloader isolation (AGENT.md sections 4 and
 * 4bis). For each plugin jar:
 *
 * <ol>
 *   <li>read and validate its {@code forge-plugin.toml} from inside the jar;</li>
 *   <li>verify its declared core API range accepts the running API
 *       ({@code FORGE-PLG-0001}) before anything loads;</li>
 *   <li>resolve its own {@code [dependencies]} and build an isolated
 *       {@link PluginClassLoader} over them plus the plugin jar;</li>
 *   <li>instantiate the {@code [plugin] main} entry point and call
 *       {@code onLoad} with a context that funnels registrations through the
 *       shared registry (so uniqueness and built-in rules always apply);</li>
 *   <li>check that the runtime registrations match the descriptor's
 *       {@code [[provides]]} ({@code FORGE-PLG-0005}).</li>
 * </ol>
 *
 * <p>Bytecode loading is pure JVM work; in the native/JVM split of section
 * 16bis this runs in full-JVM mode, where the same core classes execute the
 * whole build.</p>
 */
public final class PluginLoader {

    private final PluginDependencyResolver dependencyResolver;
    private final ClassLoader apiParent;

    /**
     * @param dependencyResolver resolves plugin {@code [dependencies]} to jars
     * @param apiParent          classloader that exposes the public plugin API
     *                           ({@code io.github.yasmramos.forge.plugin}); in
     *                           production this is the core classloader
     */
    public PluginLoader(PluginDependencyResolver dependencyResolver, ClassLoader apiParent) {
        this.dependencyResolver = dependencyResolver;
        this.apiParent = apiParent;
    }

    /**
     * Loads every given plugin jar in order, returning the catalog of loaded
     * plugins and the registry of extension points they registered.
     */
    public LoadedPlugins load(List<File> pluginJars) {
        PluginCatalog catalog = new PluginCatalog();
        PluginExtensionRegistry registry = new PluginExtensionRegistry();
        List<PluginClassLoader> classLoaders = new ArrayList<>();

        for (File jar : pluginJars) {
            PluginDescriptor descriptor = PluginDescriptorReader.readFromJar(jar);
            PluginDescriptorReader.checkCompatibility(descriptor);

            // Duplicate id fails before any bytecode from this jar runs.
            catalog.register(descriptor);

            List<String> coordinates = new ArrayList<>();
            for (java.util.Map.Entry<String, String> e : descriptor.getDependencies().entrySet()) {
                coordinates.add(e.getKey() + ":" + e.getValue());
            }
            List<Path> dependencies = coordinates.isEmpty()
                ? java.util.Collections.emptyList()
                : dependencyResolver.resolve(coordinates);

            List<Path> classpath = new ArrayList<>(dependencies);
            classpath.add(jar.toPath());
            PluginClassLoader loader = new PluginClassLoader(descriptor.getId(), classpath, apiParent);
            classLoaders.add(loader);

            ForgePlugin instance = instantiate(descriptor, loader);
            DefaultPluginContext context = new DefaultPluginContext(descriptor, registry);
            try {
                instance.onLoad(context);
            } catch (ForgeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ForgeException(ErrorCode.PLG_0009,
                    "plugin '" + descriptor.idVersion() + "' failed during onLoad: " + e, e);
            }

            // Declaration must match behavior: registry records the truth.
            PluginCatalog.verifyProvides(descriptor, registry.extensionPointsOf(descriptor.getId()));
        }

        return new LoadedPlugins(catalog, registry, classLoaders);
    }

    private static ForgePlugin instantiate(PluginDescriptor descriptor, PluginClassLoader loader) {
        String main = descriptor.getMain();
        try {
            Class<?> type = Class.forName(main, true, loader);
            if (!ForgePlugin.class.isAssignableFrom(type)) {
                throw new ForgeException(ErrorCode.PLG_0009,
                    "'" + main + "' does not implement "
                        + ForgePlugin.class.getName() + " (plugin '" + descriptor.idVersion() + "')");
            }
            Object instance = type.getDeclaredConstructor().newInstance();
            return (ForgePlugin) instance;
        } catch (ForgeException e) {
            throw e;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ForgeException(ErrorCode.PLG_0009,
                "cannot load entry point '" + main + "' of plugin '"
                    + descriptor.idVersion() + "': " + e, e);
        }
    }
}
