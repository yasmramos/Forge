package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Extension points registered by loaded plugins (AGENT.md section 4). Each
 * plugin contributes through its own context; this registry holds the union
 * and enforces the rules: built-ins are never replaced, and two plugins cannot
 * silently register the same extension point.
 *
 * <p>It records what each plugin registered so the descriptor's
 * {@code [[provides]]} can be checked against real behavior (section 4bis).</p>
 */
public final class PluginExtensionRegistry {

    /** Built-in task types a plugin must not shadow (section 4). */
    public static final Set<String> BUILT_IN_TASK_TYPES = Collections.unmodifiableSet(
        new LinkedHashSet<>(java.util.Arrays.asList(
            "compile", "test", "package", "javaExec", "clean", "resolve")));

    private final Map<String, String> taskTypes = new LinkedHashMap<>();
    private final Map<String, String> compilers = new LinkedHashMap<>();
    private final Map<String, String> testRunners = new LinkedHashMap<>();
    private final Map<String, String> packagingTypes = new LinkedHashMap<>();
    private final Map<String, io.github.yasmramos.forge.plugin.PluginTaskFactory> taskFactories =
        new LinkedHashMap<>();
    private final Map<String, io.github.yasmramos.forge.plugin.PluginCompiler> compilerImpls =
        new LinkedHashMap<>();

    /** Records a task type registered by {@code pluginId}. */
    public void registerTaskType(String pluginId, String name) {
        if (BUILT_IN_TASK_TYPES.contains(name)) {
            throw new ForgeException(ErrorCode.PLG_0007,
                "plugin '" + pluginId + "' tried to replace built-in task type '" + name + "'");
        }
        put(taskTypes, pluginId, name, "task-type");
    }

    /**
     * Records a task type together with the factory that produces its
     * instances (AGENT.md sections 1 and 4). The factory is what lets the type
     * contribute tasks to the graph; a type registered without one is listed
     * but contributes nothing.
     */
    public void registerTaskType(String pluginId, String name,
                                 io.github.yasmramos.forge.plugin.PluginTaskFactory factory) {
        registerTaskType(pluginId, name);
        if (factory != null) {
            taskFactories.put(name, factory);
        }
    }

    public void registerCompiler(String pluginId, String language) {
        put(compilers, pluginId, language, "compiler");
    }

    /**
     * Records a compiler implementation for a language (AGENT.md section 4).
     * The implementation is what lets the build compile that language; a
     * language registered without one is listed but contributes no compilation.
     */
    public void registerCompiler(String pluginId, String language,
                                 io.github.yasmramos.forge.plugin.PluginCompiler compiler) {
        registerCompiler(pluginId, language);
        if (compiler != null) {
            compilerImpls.put(language, compiler);
        }
    }

    public void registerTestRunner(String pluginId, String id) {
        put(testRunners, pluginId, id, "test-runner");
    }

    public void registerPackagingType(String pluginId, String id) {
        put(packagingTypes, pluginId, id, "packaging-type");
    }

    private static void put(Map<String, String> map, String pluginId, String name, String kind) {
        String owner = map.get(name);
        if (owner != null) {
            throw new ForgeException(ErrorCode.PLG_0006,
                kind + " '" + name + "' is registered by both '" + owner + "' and '" + pluginId + "'");
        }
        map.put(name, pluginId);
    }

    /** All extension points as "type:name", for provides verification. */
    public Set<String> extensionPoints() {
        Set<String> points = new LinkedHashSet<>();
        for (String n : taskTypes.keySet())     points.add("task-type:" + n);
        for (String n : compilers.keySet())     points.add("compiler:" + n);
        for (String n : testRunners.keySet())   points.add("test-runner:" + n);
        for (String n : packagingTypes.keySet()) points.add("packaging-type:" + n);
        return points;
    }

    /** Extension points registered by one plugin, as "type:name". */
    public Set<String> extensionPointsOf(String pluginId) {
        Set<String> points = new LinkedHashSet<>();
        collect(points, taskTypes, pluginId, "task-type");
        collect(points, compilers, pluginId, "compiler");
        collect(points, testRunners, pluginId, "test-runner");
        collect(points, packagingTypes, pluginId, "packaging-type");
        return points;
    }

    private static void collect(Set<String> out, Map<String, String> map,
                                String pluginId, String kind) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getValue().equals(pluginId)) {
                out.add(kind + ":" + e.getKey());
            }
        }
    }

    public Set<String> taskTypes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(taskTypes.keySet()));
    }

    /** Task types that declared a factory, i.e. that can contribute tasks. */
    public Set<String> taskTypesWithFactories() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(taskFactories.keySet()));
    }

    /** The factory registered for {@code taskType}, or {@code null}. */
    public io.github.yasmramos.forge.plugin.PluginTaskFactory taskFactory(String taskType) {
        return taskFactories.get(taskType);
    }

    /** Languages that declared a compiler implementation. */
    public Set<String> compilerLanguages() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(compilerImpls.keySet()));
    }

    /** All registered compiler languages, whether or not they have an implementation. */
    public Set<String> compilers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(compilers.keySet()));
    }

    /** The compiler registered for {@code language}, or {@code null}. */
    public io.github.yasmramos.forge.plugin.PluginCompiler compiler(String language) {
        return compilerImpls.get(language);
    }
}
