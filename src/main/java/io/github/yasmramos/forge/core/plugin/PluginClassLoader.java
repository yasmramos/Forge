package io.github.yasmramos.forge.core.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Isolated classloader for a single plugin (AGENT.md section 4). The plugin's
 * own jars are visible to it, but not to any other plugin, which is what
 * removes the classpath-conflict hell of a shared plugin classpath.
 *
 * <p>The parent is the core classloader, so the plugin can see the public
 * plugin API ({@code io.github.yasmramos.forge.plugin}); everything else it
 * needs is either its own declared dependency or the API. The loader is not
 * given any other plugin's classloader, so plugins stay mutually invisible.</p>
 */
public final class PluginClassLoader extends URLClassLoader {

    private final String pluginId;

    public PluginClassLoader(String pluginId, List<Path> jars, ClassLoader parent) {
        super(toUrls(jars), parent);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }

    private static URL[] toUrls(List<Path> jars) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Cannot convert to URL: " + jars.get(i), e);
            }
        }
        return urls;
    }

    @Override
    public String toString() {
        return "PluginClassLoader[" + pluginId + "]";
    }
}
