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
import io.github.yasmramos.forge.plugin.PluginCompilationRequest;
import io.github.yasmramos.forge.plugin.PluginCompilationResult;
import io.github.yasmramos.forge.plugin.PluginCompiler;
import io.github.yasmramos.forge.plugin.PluginProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns the compilers registered by loaded plugins into graph tasks (AGENT.md
 * section 4: "register compilers for languages"). For every language that
 * declared a {@link PluginCompiler}, the sources it claims (by extension) are
 * discovered under the project's source directories and compiled into the
 * classes directory.
 *
 * <p>Each language becomes one task with the discovered sources and the compile
 * classpath as declared inputs, so content-based caching (section 3) applies to
 * plugin compilers exactly as it does to the built-in Java compiler. A language
 * registered without an implementation is listed but contributes no task.</p>
 */
public final class PluginCompilationContributor {

    private final LoadedPlugins plugins;
    private final ProjectConfig config;
    private final File projectRoot;
    private final Path classesDir;
    private final BuildEnvironment environment;

    public PluginCompilationContributor(LoadedPlugins plugins, ProjectConfig config,
                                        File projectRoot, Path classesDir,
                                        BuildEnvironment environment) {
        this.plugins = plugins;
        this.config = config;
        this.projectRoot = projectRoot;
        this.classesDir = classesDir.toAbsolutePath().normalize();
        this.environment = environment;
    }

    /** True when at least one loaded plugin registered a compiler implementation. */
    public boolean hasCompilers() {
        return !plugins.registry().compilerLanguages().isEmpty();
    }

    /** Builds one compile task per registered compiler language. */
    public List<ForgeTask> tasks() {
        List<ForgeTask> tasks = new ArrayList<>();
        if (!hasCompilers()) {
            return tasks;
        }
        PluginProject project = new PluginProjectAdapter(config, projectRoot);
        for (String language : plugins.registry().compilerLanguages()) {
            PluginCompiler compiler = plugins.registry().compiler(language);
            List<File> sources = discoverSources(compiler.sourceExtension());
            if (sources.isEmpty()) {
                continue;
            }
            tasks.add(compileTask(language, compiler, sources, project));
        }
        return tasks;
    }

    private ForgeTask compileTask(String language, PluginCompiler compiler,
                                  List<File> sources, PluginProject project) {
        String taskName = language + "Compile";
        TaskType type = new TaskType("pluginCompile");
        ForgeTask task = new ForgeTask(taskName, type)
            .environment(environment)
            .input(new TaskInput.FilesInput(taskName + ":sources", sources))
            .input(new TaskInput.ValueInput(taskName + ":dependencies", String.valueOf(
                DependencyScopes.filter(config.getDependencies(), DependencyScopes.COMPILE))))
            .output(new TaskOutput(taskName + ":classes", Collections.singletonList(relative(classesDir))));

        task.action(context -> {
            DependencyResolution resolution =
                io.github.yasmramos.forge.core.resolver.AetherResolution.resolve(
                    config, DependencyScopes.COMPILE, null);
            List<File> classpath = new ArrayList<>();
            classpath.add(classesDir.toFile());
            for (DependencyInfo dep : resolution.getDependencies()) {
                if (dep.isResolved() && dep.getLocalFile().exists()) {
                    classpath.add(dep.getLocalFile());
                }
            }
            PluginCompilationRequest request = new Request(project, sources, classpath, classesDir.toFile());
            PluginCompilationResult result;
            try {
                Files.createDirectories(classesDir);
                result = compiler.compile(request);
            } catch (ForgeException e) {
                throw e;
            } catch (Exception e) {
                return TaskResult.failure("Plugin compiler '" + language
                    + "' failed: " + e.getMessage()
                    + ". Check the plugin's compiler implementation and its dependencies.");
            }
            if (result == null || !result.isSuccess()) {
                return TaskResult.failure("Plugin compiler '" + language + "' failed: "
                    + (result == null ? "no result returned" : result.getMessage()));
            }
            context.put(taskName + ".compiledFiles", sources.size());
            return TaskResult.success("Compiled " + sources.size() + " " + language + " source file(s)");
        });
        return task;
    }

    /** Sources with the compiler's extension under the project's source roots. */
    private List<File> discoverSources(String extension) {
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        Set<File> found = new LinkedHashSet<>();
        for (Path dir : sourceDirectories()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .forEach(p -> found.add(p.toFile()));
            } catch (java.io.IOException e) {
                throw new ForgeException(ErrorCode.PLG_0011,
                    "cannot scan source directory " + dir + " for '" + extension
                        + "' sources: " + e.getMessage(), e);
            }
        }
        return new ArrayList<>(found);
    }

    private List<Path> sourceDirectories() {
        Set<Path> dirs = new LinkedHashSet<>();
        String configured = config.getSourceDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            dirs.add(resolveAgainstRoot(configured));
        }
        if (config.getSourcePaths() != null) {
            for (String p : config.getSourcePaths()) {
                if (p != null && !p.trim().isEmpty()) {
                    dirs.add(resolveAgainstRoot(p));
                }
            }
        }
        if (dirs.isEmpty()) {
            dirs.add(new File(projectRoot, "src/main/java").toPath());
        }
        return new ArrayList<>(dirs);
    }

    private Path resolveAgainstRoot(String path) {
        Path p = new File(path).toPath();
        return p.isAbsolute() ? p : new File(projectRoot, path).toPath();
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

    private static final class Request implements PluginCompilationRequest {
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
