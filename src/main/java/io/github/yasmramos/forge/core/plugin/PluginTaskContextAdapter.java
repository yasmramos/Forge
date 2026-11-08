package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.core.task.TaskContext;
import io.github.yasmramos.forge.plugin.PluginProject;
import io.github.yasmramos.forge.plugin.PluginTaskContext;

/**
 * Adapts the core {@link TaskContext} to the narrow {@link PluginTaskContext}
 * a plugin task sees (AGENT.md section 4): the project view and a shared
 * key/value bag for passing values between tasks in one build.
 */
final class PluginTaskContextAdapter implements PluginTaskContext {

    private final TaskContext delegate;
    private final PluginProject project;

    PluginTaskContextAdapter(TaskContext delegate, PluginProject project) {
        this.delegate = delegate;
        this.project = project;
    }

    @Override
    public PluginProject project() {
        return project;
    }

    @Override
    public void put(String key, Object value) {
        delegate.put(key, value);
    }

    @Override
    public Object get(String key) {
        return delegate.get(key);
    }
}
