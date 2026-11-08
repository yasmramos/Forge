package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.task.BuildEnvironment;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskInput;
import io.github.yasmramos.forge.core.task.TaskOutput;
import io.github.yasmramos.forge.core.task.TaskResult;
import io.github.yasmramos.forge.core.task.TaskType;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.PluginProject;
import io.github.yasmramos.forge.plugin.PluginTask;
import io.github.yasmramos.forge.plugin.PluginTaskFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the task types registered by loaded plugins into graph tasks (AGENT.md
 * sections 1 and 4). For every type that declared a {@link PluginTaskFactory},
 * the factory is asked for its instances; each instance becomes a
 * {@link ForgeTask} whose inputs and outputs are declared so content-based
 * caching works (section 3).
 *
 * <p>Task names must be unique across built-ins and plugins; a collision fails
 * the build at configure time with {@code FORGE-PLG-0010} rather than
 * silently overwriting a core task.</p>
 */
public final class PluginTaskContributor {

    private static final Set<String> RESERVED_TASK_NAMES = Collections.unmodifiableSet(
        new LinkedHashSet<>(java.util.Arrays.asList(
            "compile", "test", "package", "javaExec", "clean", "resolve")));

    private final LoadedPlugins plugins;
    private final ProjectConfig config;
    private final File projectRoot;
    private final BuildEnvironment environment;

    public PluginTaskContributor(LoadedPlugins plugins, ProjectConfig config,
                                 File projectRoot, BuildEnvironment environment) {
        this.plugins = plugins;
        this.config = config;
        this.projectRoot = projectRoot;
        this.environment = environment;
    }

    /** True when at least one loaded plugin registered a task factory. */
    public boolean hasTasks() {
        return !plugins.registry().taskTypesWithFactories().isEmpty();
    }

    /** Builds the graph tasks contributed by plugins, in registration order. */
    public List<ForgeTask> tasks() {
        List<ForgeTask> tasks = new ArrayList<>();
        if (!hasTasks()) {
            return tasks;
        }
        PluginProject project = new PluginProjectAdapter(config, projectRoot);
        Set<String> seen = new LinkedHashSet<>();
        for (String typeName : plugins.registry().taskTypesWithFactories()) {
            PluginTaskFactory factory = plugins.registry().taskFactory(typeName);
            List<PluginTask> instances;
            try {
                instances = factory.createTasks(project);
            } catch (ForgeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ForgeException(ErrorCode.PLG_0010,
                    "plugin task type '" + typeName + "' failed to create tasks: " + e, e);
            }
            if (instances == null) {
                continue;
            }
            for (PluginTask instance : instances) {
                tasks.add(toForgeTask(typeName, instance, project, seen));
            }
        }
        return tasks;
    }

    private ForgeTask toForgeTask(String typeName, PluginTask instance,
                                  PluginProject project, Set<String> seen) {
        String name = instance.name();
        if (name == null || name.isEmpty()) {
            throw new ForgeException(ErrorCode.PLG_0010,
                "plugin task type '" + typeName + "' produced a task with no name");
        }
        if (RESERVED_TASK_NAMES.contains(name)) {
            throw new ForgeException(ErrorCode.PLG_0010,
                "plugin task '" + name + "' (type '" + typeName + "') would shadow the built-in task of the same name");
        }
        if (!seen.add(name)) {
            throw new ForgeException(ErrorCode.PLG_0010,
                "plugin task '" + name + "' is produced more than once");
        }

        ForgeTask task = new ForgeTask(name, new TaskType(typeName))
            .environment(environment);
        for (String file : instance.inputFiles()) {
            task.input(new TaskInput.FilesInput(name + ":" + file,
                Collections.singletonList(new File(file))));
        }
        for (Map.Entry<String, String> e : instance.inputValues().entrySet()) {
            task.input(new TaskInput.ValueInput(name + ":" + e.getKey(), e.getValue()));
        }
        if (!instance.outputPaths().isEmpty()) {
            task.output(new TaskOutput(name, instance.outputPaths()));
        }
        if (!instance.dependsOn().isEmpty()) {
            task.dependsOn(instance.dependsOn().toArray(new String[0]));
        }

        task.action(context -> {
            io.github.yasmramos.forge.plugin.PluginTaskResult r = instance.execute(
                new PluginTaskContextAdapter(context, project));
            return r.isSuccess() ? TaskResult.success(r.getMessage())
                : TaskResult.failure(r.getMessage());
        });
        return task;
    }
}
