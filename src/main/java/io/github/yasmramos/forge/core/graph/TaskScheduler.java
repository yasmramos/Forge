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

    public static class Report {
        public final Map<String, Outcome> outcomes;
        public Report(Map<String, Outcome> outcomes) { this.outcomes = outcomes; }
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
                                    return Outcome.FAILED; // skip: upstream failure
                                }
                            }
                        }
                        String hash = t.computeHash();
                        if (cache.isUpToDate(hash, t)) {
                            cache.restore(hash, t, context.getProjectRoot());
                            return Outcome.CACHE_HIT;
                        }
                        TaskResult r = t.getAction().execute(context);
                        if (!r.isSuccess()) {
                            return Outcome.FAILED;
                        }
                        cache.store(hash, t, context.getProjectRoot());
                        return Outcome.EXECUTED;
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
        return new Report(outcomes);
    }

    private List<String> depsOf(TaskGraph graph, ForgeTask t) {
        List<String> deps = new ArrayList<>(t.getDependsOn());
        for (String s : t.getSoftDependsOn()) {
            if (!deps.contains(s)) deps.add(s);
        }
        return deps;
    }
}
