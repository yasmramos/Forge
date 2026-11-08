package io.github.yasmramos.forge.core.packageinfo;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.plugin.LoadedPlugins;
import io.github.yasmramos.forge.core.plugin.PluginDescriptor;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskContext;
import io.github.yasmramos.forge.core.task.TaskOutput;
import io.github.yasmramos.forge.core.task.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies the reproducibility of plugin-produced outputs by double-run
 * sampling (AGENT.md section 17).
 *
 * <p>Reproducibility is guaranteed for core tasks; plugins are best-effort. A
 * plugin declares {@code [compatibility] reproducible = true} in its
 * {@code forge-plugin.toml} (section 4bis). Forge does not take that claim on
 * faith: for every task contributed by such a plugin, the outputs of the real
 * build are hashed, the task is run a second time, and the outputs are hashed
 * again. A mismatch means the plugin's output is not bit-for-bit stable.</p>
 *
 * <p>Because the check runs after the real build, the first sample is the
 * artifact that was actually produced. The outputs are preserved across the
 * second run and restored on a mismatch, so verification never changes what the
 * build delivered. Content hashes are compared, never timestamps, keeping the
 * check consistent with the cache rule (section 3).</p>
 *
 * <p>Detection is a warning ({@code FORGE-RPR-0001}) unless strict mode is
 * requested ({@code [build] reproducible = "strict"}), in which case the build
 * fails. Verification is opt-in — strict mode or an explicit request — because
 * re-running plugin tasks doubles their cost and the ADR scopes the check to
 * CI-facing commands.</p>
 */
public final class ReproducibilityVerifier {

    private static final Logger logger = LoggerFactory.getLogger(ReproducibilityVerifier.class);

    /** One detected reproducibility violation, for reporting. */
    public static final class Violation {
        private final String pluginId;
        private final String taskName;
        private final String detail;

        Violation(String pluginId, String taskName, String detail) {
            this.pluginId = pluginId;
            this.taskName = taskName;
            this.detail = detail;
        }

        public String getPluginId() { return pluginId; }
        public String getTaskName() { return taskName; }
        public String getDetail() { return detail; }

        @Override
        public String toString() {
            return "task '" + taskName + "' (plugin " + pluginId + "): " + detail;
        }
    }

    private final LoadedPlugins plugins;
    private final File projectRoot;
    private final boolean strict;

    public ReproducibilityVerifier(LoadedPlugins plugins, File projectRoot) {
        this(plugins, projectRoot, false);
    }

    public ReproducibilityVerifier(LoadedPlugins plugins, File projectRoot, boolean strict) {
        this.plugins = plugins;
        this.projectRoot = projectRoot;
        this.strict = strict;
    }

    /** True when at least one loaded plugin claims reproducibility. */
    public boolean hasClaimingPlugins() {
        if (plugins == null || plugins.isEmpty()) {
            return false;
        }
        for (PluginDescriptor d : plugins.catalog().all()) {
            if (Boolean.TRUE.equals(d.getReproducible())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the double-run check over every task contributed by a plugin that
     * claims reproducibility, and reports the outcome (AGENT.md section 17):
     * a mismatch warns with {@code FORGE-RPR-0001}, or fails the build in strict
     * mode. Returns the violations found, empty when everything matches or no
     * plugin makes the claim.
     */
    public List<Violation> verify(List<ForgeTask> tasks) {
        if (!hasClaimingPlugins() || tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Violation> violations = new ArrayList<>();
        for (ForgeTask task : tasks) {
            String pluginId = ownerOf(task);
            if (pluginId == null) {
                continue;
            }
            Violation v = verifyTask(pluginId, task);
            if (v != null) {
                violations.add(v);
            }
        }
        report(violations);
        return violations;
    }

    /**
     * Applies the policy: never silent on a mismatch. Strict mode fails with
     * {@code FORGE-RPR-0001}; otherwise the mismatch is logged loudly so a
     * plugin that lies about reproducibility cannot pass unnoticed.
     */
    private void report(List<Violation> violations) {
        if (violations.isEmpty()) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                detail.append("; ");
            }
            detail.append(violations.get(i));
        }
        if (strict) {
            throw new ForgeException(ErrorCode.RPR_0001, detail.toString());
        }
        logger.warn("{}: {}", ErrorCode.RPR_0001.getCode(), detail);
    }

    /**
     * The id of the plugin that owns this task, when that plugin claims
     * reproducibility; {@code null} otherwise. Tasks contributed through a
     * registered task type are matched by name; the plugin packaging, compiler
     * and test-runner tasks are matched by their kind, since their type name
     * ({@code pluginPackage}, ...) is not the extension point the plugin
     * declared (section 4).
     */
    private String ownerOf(ForgeTask task) {
        String typeName = task.getType().getName();
        String kind = kindOf(typeName);
        for (PluginDescriptor d : plugins.catalog().all()) {
            if (!Boolean.TRUE.equals(d.getReproducible())) {
                continue;
            }
            Set<String> points = plugins.registry().extensionPointsOf(d.getId());
            if ("task-type".equals(kind) && points.contains("task-type:" + typeName)) {
                return d.getId();
            }
            if (!"task-type".equals(kind) && points.stream().anyMatch(p -> p.startsWith(kind + ":"))) {
                return d.getId();
            }
        }
        return null;
    }

    /** Extension-point kind for a contributed task type (section 4). */
    private static String kindOf(String typeName) {
        switch (typeName) {
            case "pluginPackage": return "packaging-type";
            case "pluginCompile": return "compiler";
            case "pluginTest":    return "test-runner";
            default:              return "task-type";
        }
    }

    /**
     * Double-runs one task and compares its output tree. The first sample is the
     * output of the real build; the second is produced by re-running the action.
     * On a mismatch the first sample is restored so the build's artifact is
     * unchanged.
     */
    private Violation verifyTask(String pluginId, ForgeTask task) {
        List<Path> outputs = declaredOutputs(task);
        if (outputs.isEmpty()) {
            // The task produced nothing yet (e.g. verified outside a build): run
            // it once so there is a first sample to compare against.
            TaskResult priming = run(task);
            if (priming == null || !priming.isSuccess()) {
                return null;
            }
            outputs = declaredOutputs(task);
            if (outputs.isEmpty()) {
                return null;
            }
        }
        String first = hashOf(outputs);
        if (first == null) {
            return null;
        }

        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("forge-rpr-");
            snapshot(outputs, scratch);

            TaskResult result = run(task);
            if (result == null || !result.isSuccess()) {
                restore(outputs, scratch);
                return null;
            }

            String second = hashOf(outputs);
            if (second != null && !first.equals(second)) {
                // Restore the first run's artifact so the build is unaffected.
                restore(outputs, scratch);
                return new Violation(pluginId, task.getName(),
                    "outputs differ between two identical runs (" + abbreviate(first)
                        + " vs " + abbreviate(second) + ")");
            }
            return null;
        } catch (IOException e) {
            logger.warn("Reproducibility check skipped for '" + task.getName()
                + "': " + e.getMessage());
            return null;
        } finally {
            if (scratch != null) {
                deleteRecursively(scratch);
            }
        }
    }

    /**
     * Runs the task's action once. Returns {@code null} when it fails, logging
     * the reason: a task that cannot run twice is not a reproducibility claim
     * we can test, so the check is skipped rather than reported as a violation.
     */
    private TaskResult run(ForgeTask task) {
        try {
            TaskResult result = task.getAction().execute(new TaskContext(projectRoot));
            if (result == null || !result.isSuccess()) {
                logger.warn("Reproducibility check skipped for '" + task.getName()
                    + "': run reported failure");
                return null;
            }
            return result;
        } catch (Exception e) {
            logger.warn("Reproducibility check skipped for '" + task.getName()
                + "': run failed (" + e.getMessage() + ")");
            return null;
        }
    }

    private List<Path> declaredOutputs(ForgeTask task) {
        List<Path> files = new ArrayList<>();
        for (TaskOutput out : task.getOutputs()) {
            for (File f : out.resolve(projectRoot)) {
                Path p = f.toPath().toAbsolutePath().normalize();
                if (Files.exists(p)) {
                    files.add(p);
                }
            }
        }
        return files;
    }

    /** Copies each declared output into {@code scratch}, preserving structure. */
    private static void snapshot(List<Path> outputs, Path scratch) throws IOException {
        Files.createDirectories(scratch);
        for (int i = 0; i < outputs.size(); i++) {
            Path target = scratch.resolve(String.valueOf(i));
            copyRecursively(outputs.get(i), target);
        }
    }

    /** Restores each declared output from {@code scratch}. */
    private static void restore(List<Path> outputs, Path scratch) throws IOException {
        for (int i = 0; i < outputs.size(); i++) {
            Path source = scratch.resolve(String.valueOf(i));
            Path target = outputs.get(i);
            deleteRecursively(target);
            if (Files.exists(source)) {
                copyRecursively(source, target);
            }
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (Stream<Path> children = Files.list(source)) {
                List<Path> sorted = new ArrayList<>();
                children.forEach(sorted::add);
                Collections.sort(sorted);
                for (Path child : sorted) {
                    copyRecursively(child, target.resolve(child.getFileName().toString()));
                }
            }
        } else {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static String hashOf(List<Path> roots) {
        List<String> entries = new ArrayList<>();
        for (Path root : roots) {
            collect(root, root, entries);
        }
        if (entries.isEmpty()) {
            return null;
        }
        Collections.sort(entries); // deterministic regardless of filesystem order
        MessageDigest digest = sha256();
        for (String entry : entries) {
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private static void collect(Path root, Path base, List<String> out) {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isRegularFile(root)) {
            String rel = base.relativize(root).toString().replace('\\', '/');
            try {
                out.add(rel + "=" + hex(sha256().digest(Files.readAllBytes(root))));
            } catch (IOException e) {
                out.add(rel + "=<unreadable>");
            }
            return;
        }
        try (Stream<Path> children = Files.list(root)) {
            List<Path> sorted = new ArrayList<>();
            children.forEach(sorted::add);
            Collections.sort(sorted);
            for (Path child : sorted) {
                collect(child, base, out);
            }
        } catch (IOException e) {
            out.add(base.relativize(root).toString() + "=<unreadable>");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> children = Files.list(p)) {
                    for (Path child : children.toArray(Path[]::new)) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String abbreviate(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }
}
