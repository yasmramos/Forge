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

    /** Registers a compiler for a language, keyed by language id. */
    void registerCompiler(String language);

    /** Registers a test runner, keyed by its id. */
    void registerTestRunner(String id);

    /** Registers a packaging type, keyed by its id. */
    void registerPackagingType(String id);
}
