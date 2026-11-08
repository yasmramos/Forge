package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.core.task.BuildEnvironment;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskInput;
import io.github.yasmramos.forge.core.task.TaskOutput;
import io.github.yasmramos.forge.core.task.TaskResult;
import io.github.yasmramos.forge.core.task.TaskType;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.PluginProject;
import io.github.yasmramos.forge.plugin.PluginTestRunRequest;
import io.github.yasmramos.forge.plugin.PluginTestRunResult;
import io.github.yasmramos.forge.plugin.PluginTestRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns the test runners registered by loaded plugins into graph tasks
 * (AGENT.md section 4: "register test runners"). For every runner, the test
 * sources it claims (by extension) are discovered under the project's test
 * roots and run.
 *
 * <p>Each runner becomes one task that hard-depends on the built-in test task,
 * so tests only run after the main code compiles and the project's own tests
 * pass. The claimed test sources and the test classpath are declared inputs, so
 * content-based caching (section 3) applies: an unchanged test set hits the
 * cache instead of re-running. A runner registered without an implementation is
 * listed but contributes no task.</p>
 */
public final class PluginTestContributor {

    private final LoadedPlugins plugins;
    private final ProjectConfig config;
    private final File projectRoot;
    private final Path classesDir;
    private final BuildEnvironment environment;

    public PluginTestContributor(LoadedPlugins plugins, ProjectConfig config,
                                 File projectRoot, Path classesDir,
                                 BuildEnvironment environment) {
        this.plugins = plugins;
        this.config = config;
        this.projectRoot = projectRoot;
        this.classesDir = classesDir.toAbsolutePath().normalize();
        this.environment = environment;
    }

    /** True when at least one loaded plugin registered a test runner implementation. */
    public boolean hasRunners() {
        return !plugins.registry().testRunnersWithImpl().isEmpty();
    }

    /** Builds one test task per registered runner. */
    public List<ForgeTask> tasks() {
        List<ForgeTask> tasks = new ArrayList<>();
        if (!hasRunners()) {
            return tasks;
        }
        PluginProject project = new PluginProjectAdapter(config, projectRoot);
        for (String id : plugins.registry().testRunnersWithImpl()) {
            PluginTestRunner runner = plugins.registry().testRunner(id);
            List<File> sources = discoverTests(runner.testSourceExtension());
            if (sources.isEmpty()) {
                continue;
            }
            tasks.add(testTask(id, runner, sources, project));
        }
        return tasks;
    }

    private ForgeTask testTask(String id, PluginTestRunner runner,
                               List<File> sources, PluginProject project) {
        String taskName = id + "Test";
        TaskType type = new TaskType("pluginTest");
        ForgeTask task = new ForgeTask(taskName, type)
            .environment(environment)
            .dependsOn(io.github.yasmramos.forge.core.task.StandardTaskFactory.TEST)
            .input(new TaskInput.FilesInput(taskName + ":sources", sources))
            .input(new TaskInput.ValueInput(taskName + ":dependencies", String.valueOf(
                DependencyScopes.filter(config.getDependencies(), DependencyScopes.TEST))))
            .output(new TaskOutput(taskName + ":results",
                Collections.singletonList(relative(testOutputDir()))));

        task.action(context -> {
            DependencyResolution resolution =
                io.github.yasmramos.forge.core.resolver.AetherResolution.resolve(
                    config, DependencyScopes.TEST, null);
            List<File> classpath = new ArrayList<>();
            classpath.add(classesDir.toFile());
            for (DependencyInfo dep : resolution.getDependencies()) {
                if (dep.isResolved() && dep.getLocalFile().exists()) {
                    classpath.add(dep.getLocalFile());
                }
            }
            PluginTestRunRequest request =
                new Request(project, sources, classpath, testOutputDir().toFile());
            PluginTestRunResult result;
            try {
                Files.createDirectories(testOutputDir());
                result = runner.run(request);
            } catch (ForgeException e) {
                throw e;
            } catch (Exception e) {
                return TaskResult.failure("Plugin test runner '" + id + "' failed: " + e.getMessage()
                    + ". Check the plugin's test runner implementation and its dependencies.");
            }
            if (result == null || !result.isSuccess()) {
                String detail = result == null ? "no result returned" : result.getMessage();
                int total = result == null ? sources.size() : result.getTotal();
                int passed = result == null ? 0 : result.getPassed();
                return TaskResult.failure("Tests failed via plugin runner '" + id + "': "
                    + passed + "/" + total + " passed. " + (detail != null ? detail : ""));
            }
            context.put(taskName + ".total", result.getTotal());
            context.put(taskName + ".passed", result.getPassed());
            return TaskResult.success("Ran " + result.getTotal() + " test(s) via " + id
                + ", " + result.getPassed() + " passed");
        });
        return task;
    }

    /** Test sources with the runner's extension under the project's test roots. */
    private List<File> discoverTests(String extension) {
        return PluginSourceScanner.discover(
            PluginSourceScanner.testSourceDirectories(config, projectRoot),
            extension, ErrorCode.PLG_0012);
    }

    private Path testOutputDir() {
        return classesDir.getParent().resolve("plugin-test-results");
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

    private static final class Request implements PluginTestRunRequest {
        private final PluginProject project;
        private final List<File> sources;
        private final List<File> classpath;
        private final File outputDir;

        Request(PluginProject project, List<File> sources, List<File> classpath, File outputDir) {
            this.project = project;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.classpath = Collections.unmodifiableList(new ArrayList<>(classpath));
            this.outputDir = outputDir;
        }

        @Override public PluginProject project() { return project; }
        @Override public List<File> sourceFiles() { return sources; }
        @Override public List<File> classpath() { return classpath; }
        @Override public File outputDirectory() { return outputDir; }
    }
}
