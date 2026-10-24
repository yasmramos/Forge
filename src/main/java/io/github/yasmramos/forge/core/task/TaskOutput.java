package io.github.yasmramos.forge.core.task;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * A task output: files/directories/values produced by a task.
 * Output paths are always project-relative so the cache stays portable
 * across machines and checkouts (AGENT.md decision 3: relocatable outputs).
 */
public class TaskOutput {

    private final String name;
    private final List<String> relativePaths; // POSIX-style, relative to project root

    public TaskOutput(String name, List<String> relativePaths) {
        this.name = name;
        this.relativePaths = Collections.unmodifiableList(relativePaths);
    }

    public String getName() {
        return name;
    }

    public List<String> getRelativePaths() {
        return relativePaths;
    }

    public List<File> resolve(File projectRoot) {
        return relativePaths.stream()
            .map(p -> new File(projectRoot, p))
            .collect(java.util.stream.Collectors.toList());
    }
}
