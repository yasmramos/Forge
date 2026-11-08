package io.github.yasmramos.forge.plugin;

import java.util.List;

/**
 * A factory for tasks of a plugin-registered type (AGENT.md sections 1 and 4).
 * A plugin registers a task type together with a factory; Forge asks the
 * factory for the task instances to add to the graph, so the graph is built
 * from declared instances rather than from arbitrary plugin code.
 */
public interface PluginTaskFactory {

    /**
     * Creates the task instances of this type for the given project. Called at
     * configure time, before the graph is validated.
     */
    List<PluginTask> createTasks(PluginProject project);
}
