package io.github.yasmramos.forge.plugin;

/**
 * Context handed to a {@link PluginTask} at execution time (AGENT.md sections 1
 * and 4). Gives the task the project view and a place to stash values for
 * downstream tasks; nothing else is exposed.
 */
public interface PluginTaskContext {

    /** The project this task runs in. */
    PluginProject project();

    /** Publishes a value other tasks in the same build can read. */
    void put(String key, Object value);

    /** Reads a value published by an upstream task, or {@code null}. */
    Object get(String key);
}
