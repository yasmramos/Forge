package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.plugin.PluginContext;

/**
 * The {@link PluginContext} handed to a plugin at load time. Every registration
 * goes through the shared {@link PluginExtensionRegistry}, which owns the
 * uniqueness and built-in rules, so a plugin cannot bypass them.
 */
final class DefaultPluginContext implements PluginContext {

    private final PluginDescriptor descriptor;
    private final PluginExtensionRegistry registry;

    DefaultPluginContext(PluginDescriptor descriptor, PluginExtensionRegistry registry) {
        this.descriptor = descriptor;
        this.registry = registry;
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void registerTaskType(String name) {
        registry.registerTaskType(descriptor.getId(), name);
    }

    @Override
    public void registerTaskType(String name,
                                 io.github.yasmramos.forge.plugin.PluginTaskFactory factory) {
        registry.registerTaskType(descriptor.getId(), name, factory);
    }

    @Override
    public void registerCompiler(String language) {
        registry.registerCompiler(descriptor.getId(), language);
    }

    @Override
    public void registerCompiler(String language,
                                 io.github.yasmramos.forge.plugin.PluginCompiler compiler) {
        registry.registerCompiler(descriptor.getId(), language, compiler);
    }

    @Override
    public void registerTestRunner(String id) {
        registry.registerTestRunner(descriptor.getId(), id);
    }

    @Override
    public void registerTestRunner(String id,
                                   io.github.yasmramos.forge.plugin.PluginTestRunner runner) {
        registry.registerTestRunner(descriptor.getId(), id, runner);
    }

    @Override
    public void registerPackagingType(String id) {
        registry.registerPackagingType(descriptor.getId(), id);
    }
}
