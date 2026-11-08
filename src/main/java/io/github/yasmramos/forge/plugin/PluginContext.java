package io.github.yasmramos.forge.plugin;

import io.github.yasmramos.forge.core.plugin.PluginDescriptor;

/**
 * What a plugin may do at load time (AGENT.md section 4). Deliberately narrow:
 * a plugin can register new extension points, but cannot modify existing core
 * tasks, replace built-ins, or change another task's behavior. Hooks are the
 * only mechanism for extending behavior.
 */
public interface PluginContext {

    /** Identity of the plugin being loaded, for diagnostics and cache keys. */
    PluginDescriptor descriptor();

    /**
     * Registers a new task type. Fails if the name collides with a built-in or
     * an already-registered plugin type (section 4: built-ins are never
     * replaced).
     */
    void registerTaskType(String name);

    /**
     * Registers a new task type together with the factory that produces its
     * instances for the build graph (sections 1 and 4). Prefer this overload:
     * a type registered without a factory can be listed but cannot contribute
     * tasks. Fails on the same collisions as {@link #registerTaskType(String)}.
     */
    void registerTaskType(String name, PluginTaskFactory factory);

    /** Registers a compiler for a language, keyed by language id. */
    void registerCompiler(String language);

    /**
     * Registers a compiler for a language together with its implementation
     * (section 4). Prefer this overload: Forge discovers the sources the
     * compiler claims by {@link PluginCompiler#sourceExtension()} and compiles
     * them as part of the build. Fails on the same collisions as
     * {@link #registerCompiler(String)}.
     */
    void registerCompiler(String language, PluginCompiler compiler);

    /** Registers a test runner, keyed by its id. */
    void registerTestRunner(String id);

    /** Registers a packaging type, keyed by its id. */
    void registerPackagingType(String id);
}
