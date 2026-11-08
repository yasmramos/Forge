package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.doctor.Diagnostics;
import io.github.yasmramos.forge.model.ProjectConfig;

import javax.tools.ToolProvider;
import java.io.PrintStream;

/**
 * {@code forge doctor} - diagnose environment and project problems
 * (AGENT.md section 10).
 *
 * <p>Runs the checks in {@link Diagnostics} against the running system and the
 * loaded project, printing one line per check with a fix hint when something is
 * wrong. Exits 2 when a check fails (an environment problem per section 10bis),
 * 0 otherwise; warnings do not fail the command because they never block a
 * build.</p>
 */
public final class DoctorCommand {

    private DoctorCommand() {
    }

    public static int run(ForgeEngine engine, boolean json, PrintStream out) {
        Diagnostics.Environment env = fromSystem();
        if (engine != null) {
            env.projectRoot = engine.getProjectRoot();
            env.config = engine.getConfig();
            env.requiredJdkVersion = pinnedJdk(engine.getConfig());
        }

        Diagnostics.Report report = Diagnostics.run(env);

        if (json) {
            renderJson(report, engine != null, out);
        } else {
            renderHuman(report, engine != null, out);
        }
        return report.hasFailures() ? 2 : 0;
    }

    private static Diagnostics.Environment fromSystem() {
        Diagnostics.Environment env = new Diagnostics.Environment();
        env.javaVersion = System.getProperty("java.version", "");
        env.javaVendor = System.getProperty("java.vendor", "");
        env.javaHome = System.getProperty("java.home", "");
        env.hasCompiler = ToolProvider.getSystemJavaCompiler() != null;
        env.processors = Runtime.getRuntime().availableProcessors();
        env.maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return env;
    }

    /** The JDK version pinned in [project], falling back to source/target. */
    private static String pinnedJdk(ProjectConfig config) {
        if (config == null) {
            return null;
        }
        if (notBlank(config.getJdkVersion())) {
            return config.getJdkVersion();
        }
        if (notBlank(config.getSourceCompatibility())) {
            return config.getSourceCompatibility();
        }
        return config.getTargetCompatibility();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static void renderHuman(Diagnostics.Report report, boolean hasProject, PrintStream out) {
        out.println("Forge doctor");
        out.println();
        for (Diagnostics.Check c : report.getChecks()) {
            out.println("  " + tag(c.getSeverity()) + " " + pad(c.getName())
                + " " + c.getDetail());
            if (c.getFix() != null) {
                out.println("        fix: " + c.getFix());
            }
        }
        out.println();
        if (!hasProject) {
            out.println("No forge.toml here: only environment checks ran.");
        }
        if (report.hasFailures()) {
            out.println("Problems found: fix the FAIL lines above and run 'forge doctor' again.");
        } else if (report.hasWarnings()) {
            out.println("No blocking problems; review the warnings above.");
        } else {
            out.println("All checks passed.");
        }
    }

    private static void renderJson(Diagnostics.Report report, boolean hasProject, PrintStream out) {
        for (Diagnostics.Check c : report.getChecks()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"check\":\"").append(escape(c.getName())).append("\",");
            sb.append("\"severity\":\"").append(c.getSeverity().name().toLowerCase()).append("\",");
            sb.append("\"detail\":\"").append(escape(c.getDetail())).append("\",");
            sb.append("\"fix\":").append(c.getFix() == null ? "null" : "\"" + escape(c.getFix()) + "\"").append("}");
            out.println(sb);
        }
        out.println("{\"summary\":{\"failures\":" + count(report, Diagnostics.Severity.FAIL)
            + ",\"warnings\":" + count(report, Diagnostics.Severity.WARN)
            + ",\"project\":" + hasProject + "}}");
    }

    private static long count(Diagnostics.Report report, Diagnostics.Severity severity) {
        return report.getChecks().stream()
            .filter(c -> c.getSeverity() == severity).count();
    }

    private static String tag(Diagnostics.Severity s) {
        switch (s) {
            case OK:   return "OK  ";
            case WARN: return "WARN";
            default:   return "FAIL";
        }
    }

    private static String pad(String name) {
        StringBuilder sb = new StringBuilder(name);
        while (sb.length() < 16) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                                 .replace("\n", "\\n").replace("\r", "");
    }
}
