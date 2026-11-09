package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.graph.TaskScheduler;
import io.github.yasmramos.forge.core.scan.BuildScanGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/**
 * {@code forge build --scan} - write a local HTML build scan (AGENT.md section 19).
 *
 * <p>The scan is a self-contained page: a timeline of the tasks in the build,
 * their durations, and which came from the cache. It needs no server - the file
 * opens in a browser - and is written next to the build output
 * ({@code target/build-scan.html}) unless {@code --scan-output=<path>} is given.
 * The timestamp follows the same {@code SOURCE_DATE_EPOCH} chain as the rest of
 * the build (section 17), so two identical builds produce identical scans.</p>
 */
public final class BuildScanCommand {

    /** Relative location of the generated report under the build directory. */
    public static final String DEFAULT_FILE = "target/build-scan.html";

    private BuildScanCommand() {
    }

    /**
     * Writes the scan for a completed build.
     *
     * @param engine  the project engine
     * @param report  the finished task-graph report (carries the timings)
     * @param args    command arguments, honoring {@code --scan-output=<path>}
     * @param out     human-readable output stream
     * @return 0 on success
     */
    public static int run(ForgeEngine engine, TaskScheduler.Report report,
                          String[] args, PrintStream out) {
        if (engine == null) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "forge build --scan requires a project (no forge.toml found)");
        }
        ProjectConfig config = engine.getConfig();
        File projectRoot = engine.getProjectRoot();

        Instant timestamp = Instant.ofEpochSecond(
            io.github.yasmramos.forge.core.packageinfo.ReproducibleBuild.epochSeconds(
                projectRoot, config.getBuildSettings().getReproducibleTimestamp()));

        String html = BuildScanGenerator.generate(
            config.getName() == null ? "unknown" : config.getName(),
            report.timings, report.totalMillis, timestamp);

        File output = outputFile(projectRoot, args);
        write(output, html);

        out.println("Build scan written to " + relative(projectRoot, output));
        out.println("Open it in a browser: " + output.toURI());
        return 0;
    }

    /**
     * Resolves the scan target. {@code --scan-output=<path>} wins; otherwise the
     * default under {@code target/}. Relative paths are resolved against the
     * project root, never the process working directory.
     */
    static File outputFile(File projectRoot, String[] args) {
        for (String arg : args == null ? new String[0] : args) {
            if (arg.startsWith("--scan-output=")) {
                String path = arg.substring("--scan-output=".length());
                File file = new File(path);
                return file.isAbsolute() ? file : new File(projectRoot, path);
            }
        }
        return new File(projectRoot, DEFAULT_FILE);
    }

    /** True when the arguments request a build scan. */
    public static boolean isRequested(String[] args) {
        for (String arg : args == null ? new String[0] : args) {
            if ("--scan".equals(arg) || arg.startsWith("--scan-output=")) {
                return true;
            }
        }
        return false;
    }

    private static void write(File target, String content) {
        try {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ForgeException(ErrorCode.SCN_0001,
                "could not write build scan to " + target.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String relative(File projectRoot, File file) {
        String root = projectRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(root + File.separator)
            ? path.substring(root.length() + 1) : path;
    }
}
