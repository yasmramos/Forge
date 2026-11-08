package io.github.yasmramos.forge.plugin;

import io.github.yasmramos.forge.core.plugin.PluginDescriptor;

/**
 * The one extension point a plugin jar must expose (AGENT.md sections 4 and
 * 4bis): the class named by {@code [plugin] main} in {@code forge-plugin.toml}
 * implements this interface. Forge instantiates it in the plugin's isolated
 * classloader and calls {@link #onLoad(PluginContext)} once, before the build
 * graph is built.
 *
 * <p>This is the public plugin API. A plugin is built against the
 * {@code forge-plugin-api} artifact, which exposes this package only; core
 * classes beyond it are implementation details (section 4bis).</p>
 */
public interface ForgePlugin {

    /**
     * Called once when the plugin is loaded. Register task types, compilers,
     * test runners and packaging types through the given context. Throwing here
     * aborts the build with the plugin identified in the error.
     */
    void onLoad(PluginContext context);

    /**
     * The extension points this plugin registers, as {@code "type:name"} pairs
     * (for example {@code "task-type:kotlinCompile"}). Used to verify the
     * descriptor's {@code [[provides]]} against actual behavior (section 4bis);
     * the two must agree or the build fails.
     */
    default java.util.Set<String> provides() {
        return java.util.Collections.emptySet();
    }

    /**
     * The descriptor of this plugin, as read from the {@code forge-plugin.toml}
     * at the root of the plugin jar. Provided for diagnostics; the loader reads
     * the same file independently, so a plugin cannot lie about its identity.
     */
    default PluginDescriptor descriptor() {
        return null;
    }
}
