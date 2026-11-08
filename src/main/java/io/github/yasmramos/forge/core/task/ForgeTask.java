package io.github.yasmramos.forge.core.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atomic unit of work in the Forge task graph (AGENT.md decision 1).
 * Identified by a unique name, typed by a registered {@link TaskType}, with
 * declared inputs, outputs, hard dependencies (by task name) and soft
 * dependencies (ordering only, no forced execution).
 */
public class ForgeTask {

    private final String name;
    private final TaskType type;
    private final List<TaskInput> inputs = new ArrayList<>();
    private final List<TaskOutput> outputs = new ArrayList<>();
    private final List<String> dependsOn = new ArrayList<>();
    private final List<String> softDependsOn = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private TaskAction action = TaskAction.NOOP;
    private BuildEnvironment environment = BuildEnvironment.current();

    public ForgeTask(String name, TaskType type) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Task name must not be empty");
        }
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }
    public TaskType getType() { return type; }

    public ForgeTask input(TaskInput in) { inputs.add(in); return this; }
    public ForgeTask output(TaskOutput out) { outputs.add(out); return this; }
    public ForgeTask dependsOn(String... taskNames) {
        for (String n : taskNames) dependsOn.add(n);
        return this;
    }
    public ForgeTask softDependsOn(String... taskNames) {
        for (String n : taskNames) softDependsOn.add(n);
        return this;
    }
    public ForgeTask attribute(String key, Object value) { attributes.put(key, value); return this; }
    public ForgeTask action(TaskAction action) { this.action = action; return this; }

    /** Overrides the build environment folded into the cache key (AGENT.md section 3). */
    public ForgeTask environment(BuildEnvironment environment) {
        this.environment = environment;
        return this;
    }

    public List<TaskInput> getInputs() { return inputs; }
    public List<TaskOutput> getOutputs() { return outputs; }
    public List<String> getDependsOn() { return dependsOn; }
    public List<String> getSoftDependsOn() { return softDependsOn; }
    public Map<String, Object> getAttributes() { return attributes; }
    public TaskAction getAction() { return action; }

    /**
     * Deterministic cache key: task name + type + every input hash + the build
     * environment (toolchain, Forge version, plugins), folded into a single
     * SHA-256 digest. Any change to any component changes the key (AGENT.md
     * section 3: content-based invalidation, never time-based).
     */
    public String computeHash() {
        return computeHash(java.util.Collections.emptyMap());
    }

    /**
     * Same key derivation as {@link #computeHash()}, additionally folding in the
     * content hashes of this task's hard dependencies.
     *
     * <p>A task's result depends on what its upstream tasks produced, so its key
     * must change whenever an upstream key changes. Without this, a task that
     * consumes another task's outputs (e.g. {@code package} archiving the
     * classes produced by {@code compile}) would keep hitting its stale cache
     * entry after the upstream re-ran (AGENT.md decision 3). The scheduler
     * passes the already-computed dependency hashes; folding them here keeps the
     * rule in one place instead of trusting every task author to declare it.</p>
     *
     * @param dependencyHashes task name -&gt; that task's computed hash, for the
     *                         names in {@link #getDependsOn()}. Missing entries
     *                         (e.g. a task computed outside a graph) are skipped.
     */
    public String computeHash(Map<String, String> dependencyHashes) {
        java.security.MessageDigest digest = TaskInput.sha256();
        TaskInput.update(digest, name);
        TaskInput.update(digest, type.getName());
        // Toolchain, Forge version and plugins are part of the key (AGENT.md
        // section 3), not just the declared inputs: the same sources compiled by
        // a different JDK or a different Forge are different results.
        TaskInput.update(digest, environment.getToolchainHash());
        TaskInput.update(digest, environment.getForgeVersionHash());
        TaskInput.update(digest, environment.getPluginsHash());
        // Sort input hashes so declaration order does not affect the key.
        List<String> hashes = new ArrayList<>();
        for (TaskInput in : inputs) {
            hashes.add(in.getName() + "=" + in.hash());
        }
        java.util.Collections.sort(hashes);
        for (String h : hashes) {
            TaskInput.update(digest, h);
        }
        // Fold in hard dependencies (sorted for the same order-independence).
        List<String> depEntries = new ArrayList<>();
        if (dependencyHashes != null) {
            for (String dep : dependsOn) {
                String depHash = dependencyHashes.get(dep);
                if (depHash != null) {
                    depEntries.add(dep + "=" + depHash);
                }
            }
        }
        java.util.Collections.sort(depEntries);
        for (String d : depEntries) {
            TaskInput.update(digest, d);
        }
        return TaskInput.hex(digest.digest());
    }
}
