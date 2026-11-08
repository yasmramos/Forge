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
import io.github.yasmramos.forge.plugin.PluginPackagingRequest;
import io.github.yasmramos.forge.plugin.PluginPackagingResult;
import io.github.yasmramos.forge.plugin.PluginPackagingType;
import io.github.yasmramos.forge.plugin.PluginProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turns the packaging type a project selected into a graph task (AGENT.md
 * section 4: "register packaging types"). When {@code [project] packaging}
 * names a type a loaded plugin registered with an implementation, Forge
 * produces that artifact through it instead of the built-in jar.
 *
 * <p>The task hard-depends on the built-in test task (an artifact is packaged
 * only after the code compiles and tests pass, Maven ordering) and declares the
 * produced artifact as its output, so content-based caching (section 3) and
 * reproducible packaging (section 17) apply to plugin artifacts as they do to
 * jars.</p>
 */
public final class PluginPackagingContributor {

    private final LoadedPlugins plugins;
    private final ProjectConfig config;
    private final File projectRoot;
    private final Path classesDir;
    private final BuildEnvironment environment;

    public PluginPackagingContributor(LoadedPlugins plugins, ProjectConfig config,
                                      File projectRoot, Path classesDir,
                                      BuildEnvironment environment) {
        this.plugins = plugins;
        this.config = config;
        this.projectRoot = projectRoot;
        this.classesDir = classesDir.toAbsolutePath().normalize();
        this.environment = environment;
    }

    /** The selected packaging type's id, lower-cased; {@code "jar"} when unset. */
    public String selectedType() {
        String packaging = config.getPackaging();
        return packaging == null ? "jar" : packaging.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * True when the project selects a packaging type a loaded plugin provides.
     * A project selecting {@code "jar"} keeps the built-in packaging, and a
     * project selecting an unknown type is reported by {@link #tasks()} rather
     * than silently falling back.
     */
    public boolean isSelected() {
        String type = selectedType();
        return !"jar".equals(type) && plugins.registry().packagingType(type) != null;
    }

    /**
     * The packaging task for the selected type, or an empty list when the
     * built-in jar packaging should be used. Selecting a type no loaded plugin
     * provides fails at configure time with an actionable error, never by
     * silently producing a jar the project did not ask for.
     */
    public List<ForgeTask> tasks() {
        String type = selectedType();
        if ("jar".equals(type)) {
            return Collections.emptyList();
        }
        PluginPackagingType packaging = plugins.registry().packagingType(type);
        if (packaging == null) {
            throw new ForgeException(ErrorCode.PLG_0013,
                "packaging type '" + type + "' is selected in [project] but no loaded plugin"
                    + " provides it; available plugin packaging types: "
                    + plugins.registry().packagingTypesWithImpl());
        }
        return Collections.singletonList(packagingTask(packaging));
    }

    private ForgeTask packagingTask(PluginPackagingType packaging) {
        String taskName = "package";
        TaskType type = new TaskType("pluginPackage");
        Path outputDir = classesDir.getParent().resolve("forge-output");
        Path artifact = outputDir.resolve(packaging.artifactFileName());
        ForgeTask task = new ForgeTask(taskName, type)
            .environment(environment)
            .dependsOn(io.github.yasmramos.forge.core.task.StandardTaskFactory.TEST)
            .input(new TaskInput.FilesInput(taskName + ":classes", Collections.singletonList(classesDir.toFile())))
            .output(new TaskOutput(taskName + ":artifact", Collections.singletonList(relative(artifact))));

        task.action(context -> {
            PluginProject project = new PluginProjectAdapter(config, projectRoot);
            PluginPackagingRequest request = new Request(project, classesDir.toFile(), outputDir.toFile());
            PluginPackagingResult result;
            try {
                Files.createDirectories(outputDir);
                result = packaging.packageArtifact(request);
            } catch (ForgeException e) {
                throw e;
            } catch (Exception e) {
                return TaskResult.failure("Packaging type '" + packaging.id() + "' failed: "
                    + e.getMessage() + ". Check the plugin's packaging implementation and its dependencies.");
            }
            if (result == null || !result.isSuccess()) {
                return TaskResult.failure("Packaging type '" + packaging.id() + "' failed: "
                    + (result == null ? "no result returned" : result.getMessage()));
            }
            if (!Files.isRegularFile(artifact)) {
                return TaskResult.failure("Packaging type '" + packaging.id()
                    + "' reported success but did not write " + artifact.getFileName()
                    + " under " + outputDir + ". Write the artifact to the request's output directory.");
            }
            return TaskResult.success("Packaged " + artifact.getFileName());
        });
        return task;
    }

    /** Project-relative POSIX path for cache-portable outputs. */
    private String relative(Path absolute) {
        Path root = projectRoot.toPath().toAbsolutePath().normalize();
        Path abs = absolute.toAbsolutePath().normalize();
        try {
            return root.relativize(abs).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return abs.toString();
        }
    }

    private static final class Request implements PluginPackagingRequest {
        private final PluginProject project;
        private final File classesDir;
        private final File outputDir;

        Request(PluginProject project, File classesDir, File outputDir) {
            this.project = project;
            this.classesDir = classesDir;
            this.outputDir = outputDir;
        }

        @Override public PluginProject project() { return project; }
        @Override public File classesDirectory() { return classesDir; }
        @Override public File outputDirectory() { return outputDir; }
    }
}
