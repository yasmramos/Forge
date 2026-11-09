package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.graph.TaskScheduler;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@code forge build --scan} (AGENT.md section 19). */
class BuildScanCommandTest {

    private static TaskScheduler.Report report() {
        List<TaskScheduler.TaskTiming> timings = new ArrayList<>();
        timings.add(new TaskScheduler.TaskTiming("compile", "compile",
            TaskScheduler.Outcome.EXECUTED, 0, 120, java.util.Collections.emptyList()));
        timings.add(new TaskScheduler.TaskTiming("test", "test",
            TaskScheduler.Outcome.CACHE_HIT, 120, 5, java.util.Collections.singletonList("compile")));
        Map<String, TaskScheduler.Outcome> outcomes = new LinkedHashMap<>();
        outcomes.put("compile", TaskScheduler.Outcome.EXECUTED);
        outcomes.put("test", TaskScheduler.Outcome.CACHE_HIT);
        return new TaskScheduler.Report(outcomes, timings, 125);
    }

    private static ForgeEngine engine(File root) {
        ProjectConfig config = new ProjectConfig();
        config.setName("scan-demo");
        config.setVersion("1.0.0");
        return new ForgeEngine(config, root);
    }

    @Test
    void writesScanToDefaultLocation(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = BuildScanCommand.run(engine(tmp.toFile()), report(), new String[0],
            new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        File scan = new File(tmp.toFile(), BuildScanCommand.DEFAULT_FILE);
        assertTrue(scan.isFile(), "scan must be written to " + BuildScanCommand.DEFAULT_FILE);
        String html = new String(Files.readAllBytes(scan.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("Forge build scan"), html);
        assertTrue(html.contains("compile"), html);
    }

    @Test
    void honorsScanOutputFlag(@TempDir Path tmp) throws Exception {
        BuildScanCommand.run(engine(tmp.toFile()), report(),
            new String[] {"--scan-output=reports/scan.html"},
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertTrue(new File(tmp.toFile(), "reports/scan.html").isFile());
    }

    @Test
    void recognizesTheScanFlag() {
        assertTrue(BuildScanCommand.isRequested(new String[] {"build", "--scan"}));
        assertTrue(BuildScanCommand.isRequested(new String[] {"build", "--scan-output=x.html"}));
        assertEquals(false, BuildScanCommand.isRequested(new String[] {"build", "graph"}));
    }

    @Test
    void defaultOutputIsUnderTarget(@TempDir Path tmp) {
        assertEquals(new File(tmp.toFile(), "target/build-scan.html"),
            BuildScanCommand.outputFile(tmp.toFile(), new String[0]));
    }
}
