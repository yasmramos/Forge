package io.github.yasmramos.forge.core.scan;

import io.github.yasmramos.forge.core.graph.TaskScheduler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the build scan HTML report (AGENT.md section 19). */
class BuildScanGeneratorTest {

    private static TaskScheduler.TaskTiming timing(String name, String type,
                                                   TaskScheduler.Outcome outcome,
                                                   long start, long duration) {
        return new TaskScheduler.TaskTiming(name, type, outcome, start, duration,
            java.util.Collections.emptyList());
    }

    @Test
    void rendersASelfContainedHtmlDocument() {
        List<TaskScheduler.TaskTiming> timings = new ArrayList<>();
        timings.add(timing("compile", "compile", TaskScheduler.Outcome.EXECUTED, 0, 800));
        timings.add(timing("test", "test", TaskScheduler.Outcome.CACHE_HIT, 800, 50));

        String html = BuildScanGenerator.generate("awesome", timings, 1000,
            Instant.parse("2026-01-01T00:00:00Z"));

        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertTrue(html.contains("<title>Forge build scan - awesome</title>"), html);
        // Self-contained: no external CSS/JS references.
        assertTrue(!html.contains("<link") && !html.contains("<script"), html);
        // Summary cards reflect the outcomes.
        assertTrue(html.contains("Executed"), html);
        assertTrue(html.contains("Cache hits"), html);
        assertTrue(html.contains("cache hit"), html);
    }

    @Test
    void escapesUntrustedTaskNames() {
        List<TaskScheduler.TaskTiming> timings = new ArrayList<>();
        timings.add(timing("<script>alert(1)</script>", "compile",
            TaskScheduler.Outcome.EXECUTED, 0, 10));

        String html = BuildScanGenerator.generate("p", timings, 10, Instant.EPOCH);

        assertTrue(!html.contains("<script>alert(1)</script>"), "raw script must be escaped");
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void isDeterministicForAFixedTimestamp() {
        List<TaskScheduler.TaskTiming> timings = new ArrayList<>();
        timings.add(timing("compile", "compile", TaskScheduler.Outcome.EXECUTED, 0, 5));

        String first = BuildScanGenerator.generate("p", timings, 5, Instant.EPOCH);
        String second = BuildScanGenerator.generate("p", timings, 5, Instant.EPOCH);

        assertEquals(first, second, "identical inputs must yield identical HTML (section 17)");
    }

    @Test
    void handlesAnEmptyBuild() {
        String html = BuildScanGenerator.generate("p", new ArrayList<>(), 0, Instant.EPOCH);

        assertTrue(html.contains("No tasks were recorded"), html);
    }

    @Test
    void formatsDurationsForHumans() {
        assertEquals("<1 ms", BuildScanGenerator.formatDuration(0));
        assertEquals("12 ms", BuildScanGenerator.formatDuration(12));
        assertEquals("1.50 s", BuildScanGenerator.formatDuration(1500));
    }
}
