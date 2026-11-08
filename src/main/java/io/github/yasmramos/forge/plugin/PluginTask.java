package io.github.yasmramos.forge.plugin;

import java.util.List;
import java.util.Map;

/**
 * A task a plugin contributes to the build graph (AGENT.md sections 1 and 4).
 *
 * <p>A task is an instance of a registered type with declared inputs and
 * outputs so the content-based cache works (section 3): Forge hashes the
 * declared inputs, folds them into the cache key, and only runs the action on a
 * miss. Inputs are expressed as absolute paths, hashed by content (never by
 * time); outputs are project-relative so cached entries stay relocatable across
 * machines.</p>
 */
public interface PluginTask {

    /** Unique task name within the graph, e.g. {@code kotlinCompile}. */
    String name();

    /**
     * Files this task reads, as absolute paths. Hashed by content and folded
     * into the cache key; leave empty when the task depends only on other tasks.
     */
    default List<String> inputFiles() {
        return java.util.Collections.emptyList();
    }

    /**
     * Extra inputs that affect the result but are not files (tool versions,
     * flags). Each entry is {@code name -> value}; values must be
     * deterministically hashable strings (section 1).
     */
    default Map<String, String> inputValues() {
        return java.util.Collections.emptyMap();
    }

    /**
     * Outputs this task produces, as project-relative paths (POSIX separators),
     * e.g. {@code "target/classes"}. Relative so the cache stays portable
     * (section 3).
     */
    default List<String> outputPaths() {
        return java.util.Collections.emptyList();
    }

    /**
     * Names of tasks that must run before this one. Hard dependencies: they are
     * ordered first and their result hash is folded into this task's key, so a
     * changed upstream re-runs this task instead of restoring a stale entry.
     */
    default List<String> dependsOn() {
        return java.util.Collections.emptyList();
    }

    /** Executes the task. Only called on a cache miss (section 1, execution time). */
    PluginTaskResult execute(PluginTaskContext context);
}
