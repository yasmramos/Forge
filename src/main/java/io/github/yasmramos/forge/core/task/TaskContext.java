package io.github.yasmramos.forge.core.task;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime context handed to a {@link TaskAction}: project root and mutable
 * per-build attributes (e.g. resolved classpath entries set by upstream tasks).
 */
public class TaskContext {

    private final File projectRoot;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public TaskContext(File projectRoot) {
        this.projectRoot = projectRoot;
    }

    public File getProjectRoot() { return projectRoot; }

    public void put(String key, Object value) { data.put(key, value); }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) data.get(key); }
}
