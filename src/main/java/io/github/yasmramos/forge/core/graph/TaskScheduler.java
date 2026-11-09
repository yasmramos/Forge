package io.github.yasmramos.forge.core.graph;

import io.github.yasmramos.forge.core.cache.TaskCache;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskContext;
import io.github.yasmramos.forge.core.task.TaskResult;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Own scheduler over the task graph (AGENT.md decision 1: "the scheduler is
 * mine, not the OS'). Independent tasks run in parallel up to a thread limit.
 * Two-phase execution: the whole graph is validated and hashed by the caller
 * before this runs; here only cache-missed tasks execute.
 */
public class TaskScheduler {

    public enum Outcome { CACHE_HIT, EXECUTED, FAILED }

    /**
     * One task's contribution to the build timeline (AGENT.md section 19). The
     * build scan renders these as bars: {@code startOffsetMillis} is measured
     * from the beginning of the build, so parallel branches overlap visibly
     * instead of hiding behind a single total.
     */
    public static final class TaskTiming {
        public final String name;
        public final String type;
        public final Outcome outcome;
        public final long startOffsetMillis;
        public final long durationMillis;
        public final java.util.List<String> dependsOn;

        public TaskTiming(String name, String type, Outcome outcome,
                          long startOffsetMillis, long durationMillis,
                          java.util.List<String> dependsOn) {
            this.name = name;
            this.type = type;
            this.outcome = outcome;
            this.startOffsetMillis = startOffsetMillis;
            this.durationMillis = durationMillis;
            this.dependsOn = dependsOn;
        }
    }

    public static class Report {
        public final Map<String, Outcome> outcomes;
        /** Per-task timings in topological order; empty when timing was not recorded. */
        public final java.util.List<TaskTiming> timings;
        public final long totalMillis;

        public Report(Map<String, Outcome> outcomes) {
            this(outcomes, java.util.Collections.emptyList(), 0L);
        }

        public Report(Map<String, Outcome> outcomes, java.util.List<TaskTiming> timings,
                      long totalMillis) {
            this.outcomes = outcomes;
            this.timings = timings;
            this.totalMillis = totalMillis;
        }

        public long cacheHits() { return outcomes.values().stream().filter(o -> o == Outcome.CACHE_HIT).count(); }
        public long executed() { return outcomes.values().stream().filter(o -> o == Outcome.EXECUTED).count(); }
        public boolean failed() { return outcomes.values().stream().anyMatch(o -> o == Outcome.FAILED); }
    }

    private final int threads;

    public TaskScheduler(int threads) {
        this.threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
    }

    public Report execute(TaskGraph graph, TaskContext context, TaskCache cache) throws Exception {
        List<ForgeTask> ordered = graph.topoOrder();
        Map<String, Future<Outcome>> futures = new HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        Map<String, Outcome> outcomes = new HashMap<>();
        Map<String, TaskTiming> timings = new java.util.concurrent.ConcurrentHashMap<>();
        long buildStart = System.nanoTime();
        // Computed cache keys, published as each task finishes so dependents can
        // fold them into their own key (see ForgeTask.computeHash(Map)).
        Map<String, String> hashes = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            // Submit ready tasks; each dependent waits on its deps' futures.
            for (ForgeTask t : ordered) {
                futures.put(t.getName(), pool.submit(new Callable<Outcome>() {
                    @Override
                    public Outcome call() throws Exception {
                        // Wait for dependencies first (topologically guaranteed).
                        for (String dep : depsOf(graph, t)) {
                            Future<Outcome> f = futures.get(dep);
                            if (f != null) {
                                Outcome o = f.get();
                                if (o == Outcome.FAILED) {
                                    timings.put(t.getName(), timing(t, Outcome.FAILED, 0, 0, graph));
                                    return Outcome.FAILED; // skip: upstream failure
                                }
                            }
                        }
                        long start = System.nanoTime();
                        String hash = t.computeHash(hashes);
                        hashes.put(t.getName(), hash);
                        Outcome outcome;
                        if (cache.isUpToDate(hash, t)) {
                            cache.restore(hash, t, context.getProjectRoot());
                            outcome = Outcome.CACHE_HIT;
                        } else {
                            TaskResult r = t.getAction().execute(context);
                            if (!r.isSuccess()) {
                                outcome = Outcome.FAILED;
                            } else {
                                cache.store(hash, t, context.getProjectRoot());
                                outcome = Outcome.EXECUTED;
                            }
                        }
                        timings.put(t.getName(), timing(t, outcome,
                            (start - buildStart) / 1_000_000, (System.nanoTime() - start) / 1_000_000,
                            graph));
                        return outcome;
                    }
                }));
            }
            for (Map.Entry<String, Future<Outcome>> e : futures.entrySet()) {
                outcomes.put(e.getKey(), e.getValue().get());
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        }
        long totalMillis = (System.nanoTime() - buildStart) / 1_000_000;
        List<TaskTiming> orderedTimings = new ArrayList<>();
        for (ForgeTask t : ordered) {
            TaskTiming timing = timings.get(t.getName());
            if (timing != null) {
                orderedTimings.add(timing);
            }
        }
        return new Report(outcomes, orderedTimings, totalMillis);
    }

    private TaskTiming timing(ForgeTask t, Outcome outcome, long startOffsetMillis,
                              long durationMillis, TaskGraph graph) {
        return new TaskTiming(t.getName(), t.getType() == null ? "task" : t.getType().toString(),
            outcome, startOffsetMillis, durationMillis, depsOf(graph, t));
    }

    private List<String> depsOf(TaskGraph graph, ForgeTask t) {
        List<String> deps = new ArrayList<>(t.getDependsOn());
        for (String s : t.getSoftDependsOn()) {
            if (!deps.contains(s)) deps.add(s);
        }
        return deps;
    }
}
