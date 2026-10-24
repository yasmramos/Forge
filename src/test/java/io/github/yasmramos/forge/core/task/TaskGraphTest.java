package io.github.yasmramos.forge.core.task;

import io.github.yasmramos.forge.core.cache.TaskCache;
import io.github.yasmramos.forge.core.graph.TaskGraph;
import io.github.yasmramos.forge.core.graph.TaskScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the AGENT.md task model: DAG validation, hashing, cache, scheduler. */
class TaskGraphTest {

    private ForgeTask task(String name, String type) {
        return new ForgeTask(name, new TaskTypeRegistry().get(type));
    }

    @Test
    void detectsCycleWithExplicitPath() {
        TaskGraph g = new TaskGraph();
        g.add(task("a", "compile").dependsOn("b"));
        g.add(task("b", "test").dependsOn("a"));
        IllegalStateException e = assertThrows(IllegalStateException.class, g::validate);
        assertTrue(e.getMessage().contains("cycle detected"), e.getMessage());
    }

    @Test
    void rejectsUnknownDependency() {
        TaskGraph g = new TaskGraph();
        g.add(task("a", "compile").dependsOn("ghost"));
        IllegalStateException e = assertThrows(IllegalStateException.class, g::validate);
        assertTrue(e.getMessage().contains("ghost"));
    }

    @Test
    void topoOrderPlacesDependenciesFirst() {
        TaskGraph g = new TaskGraph();
        g.add(task("package", "package").dependsOn("compile"));
        g.add(task("compile", "compile").dependsOn("resolve"));
        g.add(task("resolve", "resolve"));
        g.validate();
        assertEquals(Arrays.asList("resolve", "compile", "package"),
            g.topoOrder().stream().map(ForgeTask::getName).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void hashIsDeterministicAndInputSensitive(@TempDir File tmp) throws Exception {
        File src = new File(tmp, "A.java");
        Files.write(src.toPath(), "class A {}".getBytes(StandardCharsets.UTF_8));

        ForgeTask t1 = task("compile", "compile")
            .input(new TaskInput.FilesInput("sources", Collections.singletonList(src)));
        String h1 = t1.computeHash();
        assertEquals(h1, t1.computeHash(), "same inputs must produce same hash");

        Files.write(src.toPath(), "class A { int x; }".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(h1, t1.computeHash(), "content change must change the hash");
    }

    @Test
    void declarationOrderDoesNotAffectHash() {
        ForgeTask a = task("t", "compile")
            .input(new TaskInput.ValueInput("p1", "v1"))
            .input(new TaskInput.ValueInput("p2", "v2"));
        ForgeTask b = task("t", "compile")
            .input(new TaskInput.ValueInput("p2", "v2"))
            .input(new TaskInput.ValueInput("p1", "v1"));
        assertEquals(a.computeHash(), b.computeHash());
    }

    @Test
    void cacheStoreRestoreRoundTrip(@TempDir File root) throws Exception {
        File outDir = new File(root, "build/classes");
        outDir.mkdirs();
        Files.write(new File(outDir, "A.class").toPath(), "bytes".getBytes(StandardCharsets.UTF_8));

        ForgeTask t = task("compile", "compile")
            .output(new TaskOutput("classes", Collections.singletonList("build/classes")));
        TaskCache cache = new TaskCache(root);
        String hash = t.computeHash();

        assertFalse(cache.isUpToDate(hash, t));
        cache.store(hash, t, root);
        assertTrue(cache.isUpToDate(hash, t));

        // Delete outputs, then restore from cache.
        new File(outDir, "A.class").delete();
        cache.restore(hash, t, root);
        assertTrue(new File(outDir, "A.class").isFile(), "output restored from cache");
    }

    @Test
    void schedulerRunsParallelTasksAndHitsCache(@TempDir File root) throws Exception {
        AtomicInteger runs = new AtomicInteger();
        TaskGraph g = new TaskGraph();
        ForgeTask a = task("a", "compile")
            .input(new TaskInput.ValueInput("seed", "1"))
            .output(new TaskOutput("out", Collections.singletonList("build/a.txt")))
            .action(ctx -> {
                runs.incrementAndGet();
                File f = new File(ctx.getProjectRoot(), "build/a.txt");
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), "A".getBytes(StandardCharsets.UTF_8));
                return TaskResult.success();
            });
        g.add(a);
        g.validate();

        TaskCache cache = new TaskCache(root);
        TaskContext ctx = new TaskContext(root);
        TaskScheduler.Report r1 = new TaskScheduler(2).execute(g, ctx, cache);
        assertEquals(TaskScheduler.Outcome.EXECUTED, r1.outcomes.get("a"));
        assertEquals(1, runs.get());

        // Second run with identical inputs: cache hit, action not re-executed.
        TaskScheduler.Report r2 = new TaskScheduler(2).execute(g, ctx, cache);
        assertEquals(TaskScheduler.Outcome.CACHE_HIT, r2.outcomes.get("a"));
        assertEquals(1, runs.get(), "cached task must not execute again");
    }

    @Test
    void builtInTypesCannotBeShadowedByPlugins() {
        TaskTypeRegistry reg = new TaskTypeRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.register("compile"));
        assertEquals("greet", reg.register("greet").getName());
    }
}
