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

    public List<TaskInput> getInputs() { return inputs; }
    public List<TaskOutput> getOutputs() { return outputs; }
    public List<String> getDependsOn() { return dependsOn; }
    public List<String> getSoftDependsOn() { return softDependsOn; }
    public Map<String, Object> getAttributes() { return attributes; }
    public TaskAction getAction() { return action; }

    /**
     * Deterministic cache key: task name + type + every input hash, folded into
     * a single SHA-256 digest. Any change to any component changes the key
     * (AGENT.md decision 3: content-based invalidation, never time-based).
     */
    public String computeHash() {
        java.security.MessageDigest digest = TaskInput.sha256();
        TaskInput.update(digest, name);
        TaskInput.update(digest, type.getName());
        // Sort input hashes so declaration order does not affect the key.
        List<String> hashes = new ArrayList<>();
        for (TaskInput in : inputs) {
            hashes.add(in.getName() + "=" + in.hash());
        }
        java.util.Collections.sort(hashes);
        for (String h : hashes) {
            TaskInput.update(digest, h);
        }
        return TaskInput.hex(digest.digest());
    }
}
