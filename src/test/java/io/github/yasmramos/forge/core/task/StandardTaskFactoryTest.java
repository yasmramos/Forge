package io.github.yasmramos.forge.core.task;

import io.github.yasmramos.forge.core.cache.TaskCache;
import io.github.yasmramos.forge.core.graph.TaskGraph;
import io.github.yasmramos.forge.core.graph.TaskScheduler;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the standard task graph wired into the scheduler and
 * the content-based cache (AGENT.md decisions 1, 3 and 5).
 */
class StandardTaskFactoryTest {

    @TempDir
    File root;

    private ProjectConfig configWithSources() throws Exception {
        Path src = root.toPath().resolve("src/main/java");
        Files.createDirectories(src);
        Files.write(src.resolve("Hello.java"),
            ("public class Hello { public static void main(String[] a) { } }")
                .getBytes(StandardCharsets.UTF_8));

        ProjectConfig config = new ProjectConfig();
        config.setName("graph-demo");
        config.setVersion("0.1.0");
        config.setSourceDirectory(src.toString());
        // Absolute output dir so paths do not depend on the process CWD.
        config.setOutputDirectory(new File(root, "build/classes").getAbsolutePath());
        return config;
    }

    @Test
    void graphValidatesAndRunsCompileThenPackage() throws Exception {
        ProjectConfig config = configWithSources();
        StandardTaskFactory factory = new StandardTaskFactory(
            config, root, new File(root, "build/classes").toPath());

        TaskGraph graph = new TaskGraph();
        graph.add(factory.compile());
        graph.add(factory.test());
        graph.add(factory.packageTask());
        graph.validate(); // no dangling refs, no cycles

        TaskCache cache = new TaskCache(root);
        TaskScheduler scheduler = new TaskScheduler(2);
        TaskScheduler.Report report =
            scheduler.execute(graph, new TaskContext(root), cache);

        assertEquals(TaskScheduler.Outcome.EXECUTED, report.outcomes.get("compile"));
        assertEquals(TaskScheduler.Outcome.EXECUTED, report.outcomes.get("test"));
        assertEquals(TaskScheduler.Outcome.EXECUTED, report.outcomes.get("package"));
        assertTrue(new File(root, "build/classes/Hello.class").isFile(),
            "compile task produced class files");
        assertTrue(new File(root, "build/forge-output/forge-main.jar").isFile(),
            "package task produced the jar");
    }

    @Test
    void secondRunHitsCacheForAllTasks() throws Exception {
        ProjectConfig config = configWithSources();
        StandardTaskFactory factory = new StandardTaskFactory(
            config, root, new File(root, "build/classes").toPath());

        TaskCache cache = new TaskCache(root);

        TaskGraph first = new TaskGraph();
        first.add(factory.compile());
        first.add(factory.test());
        first.add(factory.packageTask());
        TaskScheduler.Report r1 = new TaskScheduler(2).execute(first, new TaskContext(root), cache);
        assertEquals(0, r1.cacheHits());

        // Remove outputs: a cache hit must restore them from content, not re-run.
        deleteRecursively(new File(root, "build"));

        TaskGraph second = new TaskGraph();
        second.add(factory.compile());
        second.add(factory.test());
        second.add(factory.packageTask());
        TaskScheduler.Report r2 = new TaskScheduler(2).execute(second, new TaskContext(root), cache);

        assertEquals(TaskScheduler.Outcome.CACHE_HIT, r2.outcomes.get("compile"));
        assertEquals(TaskScheduler.Outcome.CACHE_HIT, r2.outcomes.get("test"));
        assertEquals(TaskScheduler.Outcome.CACHE_HIT, r2.outcomes.get("package"));
        assertTrue(new File(root, "build/classes/Hello.class").isFile(),
            "cached compile output restored after deletion");
        assertTrue(new File(root, "build/forge-output/forge-main.jar").isFile(),
            "cached package output restored after deletion");
    }

    @Test
    void sourceChangeInvalidatesCompileOnly() throws Exception {
        ProjectConfig config = configWithSources();
        StandardTaskFactory factory = new StandardTaskFactory(
            config, root, new File(root, "build/classes").toPath());
        TaskCache cache = new TaskCache(root);

        runOnce(factory, cache);

        // Touch a source file: compile hash changes, so it must re-execute.
        Path src = root.toPath().resolve("src/main/java/Hello.java");
        Files.write(src, ("public class Hello { int x; public static void main(String[] a) { } }")
            .getBytes(StandardCharsets.UTF_8));

        TaskGraph g = new TaskGraph();
        g.add(factory.compile());
        g.add(factory.test());
        g.add(factory.packageTask());
        TaskScheduler.Report r = new TaskScheduler(2).execute(g, new TaskContext(root), cache);

        assertEquals(TaskScheduler.Outcome.EXECUTED, r.outcomes.get("compile"),
            "changed sources must invalidate the compile task");
        assertEquals(TaskScheduler.Outcome.CACHE_HIT, r.outcomes.get("test"),
            "test inputs unchanged -> still cached");
    }

    private void runOnce(StandardTaskFactory factory, TaskCache cache) throws Exception {
        TaskGraph g = new TaskGraph();
        g.add(factory.compile());
        g.add(factory.test());
        g.add(factory.packageTask());
        new TaskScheduler(2).execute(g, new TaskContext(root), cache);
    }

    private static void deleteRecursively(File dir) {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        dir.delete();
    }
}
