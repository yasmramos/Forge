package io.github.yasmramos.forge.core.task;

/**
 * Registry of known task types. Built-in types are fixed; plugins may register
 * new types through {@link TaskTypeRegistry}. A task is always an instance of a
 * registered type (AGENT.md decision 1: "the type exists before the instances").
 */
public final class TaskType {

    public static final String COMPILE = "compile";
    public static final String TEST = "test";
    public static final String PACKAGE = "package";
    public static final String EXEC = "exec";
    public static final String CLEAN = "clean";
    public static final String RESOLVE = "resolve";

    private final String name;

    public TaskType(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Task type name must not be empty");
        }
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TaskType && ((TaskType) o).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
