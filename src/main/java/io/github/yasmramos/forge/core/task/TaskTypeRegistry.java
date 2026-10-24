package io.github.yasmramos.forge.core.task;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of task types. Built-ins are pre-registered; plugins can add
 * new types but cannot replace built-ins (AGENT.md decision 4: plugins must not
 * override core behavior).
 */
public class TaskTypeRegistry {

    private static final Set<String> BUILT_INS = new LinkedHashSet<>(
        java.util.Arrays.asList(
            TaskType.COMPILE, TaskType.TEST, TaskType.PACKAGE,
            TaskType.JAVA_EXEC, TaskType.CLEAN, TaskType.RESOLVE));

    private final Map<String, TaskType> types = new ConcurrentHashMap<>();

    public TaskTypeRegistry() {
        for (String name : BUILT_INS) {
            types.put(name, new TaskType(name));
        }
    }

    /** Registers a plugin-provided task type. Fails if it tries to shadow a built-in. */
    public TaskType register(String name) {
        if (BUILT_INS.contains(name)) {
            throw new IllegalArgumentException(
                "Cannot re-register built-in task type '" + name + "'");
        }
        return types.computeIfAbsent(name, TaskType::new);
    }

    public TaskType get(String name) {
        TaskType t = types.get(name);
        if (t == null) {
            throw new IllegalArgumentException("Unknown task type '" + name
                + "'. Known types: " + names());
        }
        return t;
    }

    public boolean isKnown(String name) {
        return types.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(types.keySet()));
    }
}
