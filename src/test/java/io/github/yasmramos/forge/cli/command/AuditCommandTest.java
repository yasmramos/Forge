package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.audit.OssIndexClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@code forge audit} reporting and gating logic (AGENT.md section 18),
 * without touching the network: the renderer and the exit-code policy are pure
 * functions of the OSS Index reports.
 */
class AuditCommandTest {

    private static OssIndexClient.Vulnerability vuln(String id, double score) {
        return new OssIndexClient.Vulnerability(id, "title " + id, score, null, id, null);
    }

    private static OssIndexClient.ComponentReport report(String purl,
                                                         OssIndexClient.Vulnerability... vulns) {
        return new OssIndexClient.ComponentReport(purl, Arrays.asList(vulns));
    }

    @Test
    void exitsNonZeroWhenAFindingMeetsTheThreshold() {
        List<OssIndexClient.ComponentReport> reports = Arrays.asList(
            report("pkg:maven/a/b@1.0.0", vuln("CVE-1", 9.8)));
        assertEquals(1, AuditCommand.exitCodeFor(reports, "high"));
        assertEquals(1, AuditCommand.exitCodeFor(reports, "critical"));
    }

    @Test
    void thresholdGovernsTheExitCode() {
        List<OssIndexClient.ComponentReport> medium = Arrays.asList(
            report("pkg:maven/a/b@1.0.0", vuln("CVE-2", 5.0)));
        assertEquals(0, AuditCommand.exitCodeFor(medium, "high"));
        assertEquals(1, AuditCommand.exitCodeFor(medium, "medium"));
        assertEquals(0, AuditCommand.exitCodeFor(medium, "none"));
    }

    @Test
    void cleanGraphNeverFails() {
        List<OssIndexClient.ComponentReport> clean = Arrays.asList(
            report("pkg:maven/a/b@1.0.0"));
        assertEquals(0, AuditCommand.exitCodeFor(clean, "low"));
    }

    @Test
    void rendersSeveritySummaryAndFindings() {
        List<OssIndexClient.ComponentReport> reports = new ArrayList<>();
        reports.add(report("pkg:maven/a/b@1.0.0", vuln("CVE-1", 9.8)));
        reports.add(report("pkg:maven/a/c@2.0.0", vuln("CVE-2", 5.0)));
        reports.add(report("pkg:maven/a/d@3.0.0"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AuditCommand.render(reports, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains("critical"));
        assertTrue(output.contains("medium"));
        assertTrue(output.contains("CVE-1"));
        assertTrue(output.contains("Clean components: 1 of 3"));
    }

    @Test
    void rendersCleanGraphExplicitly() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        AuditCommand.render(Collections.singletonList(report("pkg:maven/a/b@1.0.0")),
            new PrintStream(buffer, true, StandardCharsets.UTF_8));
        assertTrue(buffer.toString(StandardCharsets.UTF_8)
            .contains("No known vulnerabilities found."));
    }
}
