package io.github.yasmramos.forge.core.packageinfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the single timestamp used for every entry of a reproducible
 * artifact (AGENT.md decision 17).
 *
 * <p>The order is fixed and deterministic so two machines agree on the value:</p>
 * <ol>
 *   <li>the {@code SOURCE_DATE_EPOCH} environment variable, when set;</li>
 *   <li>{@code [build] reproducible_timestamp} from {@code forge.toml};</li>
 *   <li>the commit timestamp of {@code HEAD}, when the project is a git
 *       checkout;</li>
 *   <li>the ZIP epoch minimum, {@code 1980-02-01T00:00:00Z}.</li>
 * </ol>
 *
 * <p>Git tags are deliberately not consulted: versioning is not bound to tags
 * (ADR section 6), so neither is reproducibility.</p>
 */
public final class ReproducibleBuild {

    /** Fallback epoch: the ZIP format's minimum representable date. */
    public static final long FALLBACK_EPOCH_SECONDS = 318211200L; // 1980-02-01T00:00:00Z

    private ReproducibleBuild() {
    }

    /**
     * @param projectRoot directory that may be a git checkout
     * @param configured  the {@code [build] reproducible_timestamp} value, or
     *                    {@code null} when unset (ISO-8601 or epoch seconds)
     * @return seconds since the Unix epoch
     */
    public static long epochSeconds(File projectRoot, String configured) {
        String env = System.getenv("SOURCE_DATE_EPOCH");
        if (env != null && !env.trim().isEmpty()) {
            Long parsed = parseEpoch(env.trim());
            if (parsed != null) {
                return parsed;
            }
        }
        if (configured != null && !configured.trim().isEmpty()) {
            Long parsed = parseEpoch(configured.trim());
            if (parsed != null) {
                return parsed;
            }
        }
        Long git = gitCommitTimestamp(projectRoot);
        if (git != null) {
            return git;
        }
        return FALLBACK_EPOCH_SECONDS;
    }

    /** Accepts raw epoch seconds or an ISO-8601 instant; {@code null} if invalid. */
    private static Long parseEpoch(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // not raw seconds; try ISO-8601 below
        }
        try {
            return Instant.parse(value).getEpochSecond();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long gitCommitTimestamp(File projectRoot) {
        if (projectRoot == null || !new File(projectRoot, ".git").exists()) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "git", "log", "-1", "--format=%ct");
            pb.directory(projectRoot);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return Long.parseLong(out);
        } catch (Exception e) {
            return null;
        }
    }
}
