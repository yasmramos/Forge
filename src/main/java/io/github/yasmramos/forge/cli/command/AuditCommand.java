package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.audit.OssIndexClient;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code forge audit} - query OSS Index for known vulnerabilities in the
 * resolved dependency graph (AGENT.md section 18).
 *
 * <p>Vulnerability scanning is optional and never on the build path: it is a
 * separate command the user runs when they want it. When the service cannot be
 * reached, Forge fails with an actionable network error (exit 2) rather than
 * reporting a falsely clean result; {@code --offline} opts out of the query
 * entirely. The exit code reflects the findings: it is non-zero when a
 * vulnerability at or above the configured severity is found
 * ({@code [build] audit_fail_on}, default {@code high}), so the command is
 * usable as a CI gate.</p>
 */
public final class AuditCommand {

    private AuditCommand() {
    }

    public static int run(ForgeEngine engine, String[] args, PrintStream out) {
        if (engine == null) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "forge audit requires a project (no forge.toml found)");
        }
        ProjectConfig config = engine.getConfig();
        boolean offline = hasFlag(args, "--offline");

        List<String> coordinates = coordinatesOf(DependencyCommand.resolveCompileGraph());
        if (coordinates.isEmpty()) {
            throw new ForgeException(ErrorCode.AUD_0001,
                "no dependencies to audit in " + config.getName());
        }

        out.println("Auditing " + coordinates.size() + " dependencies against OSS Index...");
        if (offline) {
            out.println("--offline: skipping the OSS Index query. Nothing was checked.");
            return 0;
        }

        OssIndexClient client = new OssIndexClient(
            config.getBuildSettings().getAuditOssIndexUrl(),
            env("FORGE_OSS_INDEX_USER"), env("FORGE_OSS_INDEX_TOKEN"));
        List<OssIndexClient.ComponentReport> reports = client.report(coordinates);

        render(reports, out);
        return exitCodeFor(reports, config.getBuildSettings().getAuditFailOn());
    }

    /** The {@code group:artifact:version} coordinates of the resolved artifacts. */
    static List<String> coordinatesOf(AetherDependencyResolver.ResolvedGraph graph) {
        List<String> coordinates = new ArrayList<>();
        for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
            coordinates.add(a.getCoordinates());
        }
        return coordinates;
    }

    /**
     * Prints the findings grouped by severity, most severe first. Components
     * without vulnerabilities are summarized, not listed, so the report stays
     * readable on large graphs.
     */
    static void render(List<OssIndexClient.ComponentReport> reports, PrintStream out) {
        List<OssIndexClient.Vulnerability> all = new ArrayList<>();
        Map<String, List<String>> affectedBySeverity = new LinkedHashMap<>();
        int clean = 0;
        for (OssIndexClient.ComponentReport report : reports) {
            if (report.getVulnerabilities().isEmpty()) {
                clean++;
                continue;
            }
            for (OssIndexClient.Vulnerability v : report.getVulnerabilities()) {
                all.add(v);
                affectedBySeverity
                    .computeIfAbsent(v.severity(), k -> new ArrayList<>())
                    .add(report.getPurl());
            }
        }

        out.println();
        out.println("Findings by severity:");
        for (String severity : new String[] {"critical", "high", "medium", "low", "unknown"}) {
            List<String> purls = affectedBySeverity.get(severity);
            if (purls != null) {
                out.println("  " + pad(severity) + " " + purls.size());
            }
        }
        if (all.isEmpty()) {
            out.println("  No known vulnerabilities found.");
        }
        out.println("  Clean components: " + clean + " of " + reports.size());

        for (OssIndexClient.Vulnerability v : all) {
            out.println();
            out.println("[" + v.severity().toUpperCase() + "] " + v.getId()
                + " (CVSS " + v.getCvssScore() + ")");
            if (v.getCve() != null && !v.getCve().equals(v.getId())) {
                out.println("  " + v.getCve());
            }
            if (v.getTitle() != null) {
                out.println("  " + v.getTitle());
            }
            if (v.getReference() != null) {
                out.println("  " + v.getReference());
            }
        }
    }

    /**
     * Exit code for the findings: 0 when nothing meets the threshold, 1
     * otherwise. {@code none} never fails, so audit can be run informationally.
     */
    static int exitCodeFor(List<OssIndexClient.ComponentReport> reports, String failOn) {
        int threshold = rank(failOn);
        if (threshold < 0) {
            return 0;
        }
        for (OssIndexClient.ComponentReport report : reports) {
            for (OssIndexClient.Vulnerability v : report.getVulnerabilities()) {
                if (rank(v.severity()) >= threshold) {
                    return 1;
                }
            }
        }
        return 0;
    }

    /** Ordering of severities; {@code none} is below every finding. */
    private static int rank(String severity) {
        if (severity == null) {
            return 2; // unknown findings count as medium
        }
        switch (severity.trim().toLowerCase()) {
            case "none": return -1;
            case "low": return 1;
            case "medium": return 2;
            case "high": return 3;
            case "critical": return 4;
            case "unknown": return 2;
            default: return 2;
        }
    }

    private static String pad(String value) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < 9) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args == null ? new String[0] : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
