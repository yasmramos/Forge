package io.github.yasmramos.forge.core.graph;

import io.github.yasmramos.forge.core.task.ForgeTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit directed acyclic graph of tasks (AGENT.md decision 1).
 * Cycle detection is mandatory before anything executes.
 */
public class TaskGraph {

    private final Map<String, ForgeTask> tasks = new LinkedHashMap<>();

    public void add(ForgeTask task) {
        if (tasks.containsKey(task.getName())) {
            throw new IllegalArgumentException("Duplicate task name: " + task.getName());
        }
        tasks.put(task.getName(), task);
    }

    public ForgeTask get(String name) {
        ForgeTask t = tasks.get(name);
        if (t == null) {
            throw new IllegalArgumentException("No such task: '" + name
                + "'. Declared tasks: " + tasks.keySet());
        }
        return t;
    }

    public Collection<ForgeTask> all() {
        return tasks.values();
    }

    /** Fails fast with the exact cycle path if the graph is not acyclic. */
    public void validate() {
        // Every declared dependency must reference an existing task (checked first,
        // so dangling references produce a clear validation message, not a lookup error).
        for (ForgeTask t : tasks.values()) {
            for (String dep : combined(t)) {
                if (!tasks.containsKey(dep)) {
                    throw new IllegalStateException("Task '" + t.getName()
                        + "' depends on unknown task '" + dep
                        + "'. Fix: declare that task or remove the dependency.");
                }
            }
        }
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> done = new LinkedHashSet<>();
        for (String name : tasks.keySet()) {
            dfs(name, visiting, done);
        }
    }

    private List<String> combined(ForgeTask t) {
        List<String> all = new ArrayList<>(t.getDependsOn());
        all.addAll(t.getSoftDependsOn());
        return all;
    }

    private void dfs(String name, Set<String> visiting, Set<String> done) {
        if (done.contains(name)) return;
        if (!visiting.add(name)) {
            List<String> cycle = new ArrayList<>(visiting);
            int start = cycle.indexOf(name);
            throw new IllegalStateException("Dependency cycle detected: "
                + String.join(" -> ", cycle.subList(start, cycle.size())) + " -> " + name);
        }
        ForgeTask task = get(name);
        for (String dep : combined(task)) {
            dfs(dep, visiting, done);
        }
        visiting.remove(name);
        done.add(name);
    }

    /**
     * Topological order (dependencies first). Assumes {@link #validate()} passed.
     */
    public List<ForgeTask> topoOrder() {
        List<ForgeTask> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (String name : tasks.keySet()) {
            visit(name, done, out);
        }
        return out;
    }

    private void visit(String name, Set<String> done, List<ForgeTask> out) {
        if (!done.add(name)) return;
        for (String dep : combined(get(name))) {
            visit(dep, done, out);
        }
        out.add(get(name));
    }
}
