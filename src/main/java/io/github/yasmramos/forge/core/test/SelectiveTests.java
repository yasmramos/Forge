package io.github.yasmramos.forge.core.test;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlParser;

import io.github.yasmramos.forge.core.SourceDependencies;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Selective test execution (AGENT.md section 21): decide which test classes a
 * change can affect, so a build runs the relevant tests instead of all of them.
 *
 * <p>The decision is derived from the same source dependency graph the compiler
 * uses for cache invalidation ({@link SourceDependencies}): a test is re-run when
 * its own effective hash — content plus its transitive source dependencies, main
 * and test alike — differs from the hash recorded by the last green run. Because
 * the hash already folds in dependencies, a change to a main class re-runs every
 * test that touches it and nothing else.</p>
 *
 * <p>The recorded state lives in {@code .forge/test-state.toml} as relative
 * POSIX paths and content hashes, so it is portable across checkouts and
 * machines (section 17) and invalidated by content, never by time (section 3).
 * It is derived data: a missing or unreadable state file means "run everything",
 * never a build failure. State is written only after a fully green run, so a
 * failure is never remembered as a pass.</p>
 *
 * <p>Selection is deliberately conservative. An unknown file, a first run with
 * no baseline, or any change to a resource (which the source graph cannot see)
 * all fall back to running every test.</p>
 */
public final class SelectiveTests {

    /** Directory holding Forge's per-project derived state. */
    static final String STATE_DIR = ".forge";
    /** File recording the hashes of the last green test run. */
    static final String STATE_FILE = "test-state.toml";

    private static final String SCHEMA = "1";

    private SelectiveTests() {
    }

    /** Outcome of selection: the tests to run and why the set was chosen. */
    public static final class Selection {
        private final List<Path> affected;
        private final int total;
        private final boolean all;
        private final boolean baseline;

        Selection(List<Path> affected, int total, boolean all, boolean baseline) {
            this.affected = affected;
            this.total = total;
            this.all = all;
            this.baseline = baseline;
        }

        /** Test sources that must run. */
        public List<Path> getAffected() {
            return affected;
        }

        /** Number of test sources considered. */
        public int getTotal() {
            return total;
        }

        /** True when every test must run (no usable baseline). */
        public boolean isAll() {
            return all;
        }

        /** True when this is the first run: no state file was found. */
        public boolean isBaseline() {
            return baseline;
        }

        /** Test sources that were skipped because nothing they depend on changed. */
        public int getSkipped() {
            return total - affected.size();
        }
    }

    /**
     * Chooses the tests a change can affect.
     *
     * @param projectRoot  directory holding the project; state lives under it
     * @param mainSources  main source files (their changes cascade into tests)
     * @param testSources  test source files (candidates for selection)
     * @param resourceDirs resource directories; any content change re-runs all,
     *                     since the source graph cannot see resource usage
     * @return the selection; {@link Selection#isAll()} when nothing can be
     *         narrowed down safely
     */
    public static Selection select(File projectRoot, List<Path> mainSources,
                                   List<Path> testSources, List<Path> resourceDirs) {
        if (testSources == null || testSources.isEmpty()) {
            return new Selection(Collections.emptyList(), 0, false, false);
        }

        Map<String, String> stored = readState(projectRoot);
        if (stored == null) {
            // No usable baseline: run everything, then record it.
            return new Selection(new ArrayList<>(testSources), testSources.size(), true, true);
        }

        String currentResources = resourceHash(resourceDirs);
        String storedResources = stored.get("resource_hash");
        if (storedResources == null || !storedResources.equals(currentResources)) {
            // A resource changed (or predates this state): re-run all.
            return new Selection(new ArrayList<>(testSources), testSources.size(), true, false);
        }

        List<Path> all = new ArrayList<>(mainSources == null ? Collections.emptyList() : mainSources);
        all.addAll(testSources);
        Map<Path, String> hashes = SourceDependencies.effectiveHashes(all);

        List<Path> affected = new ArrayList<>();
        for (Path test : testSources) {
            String recorded = stored.get(relativeKey(projectRoot, test));
            String current = hashes.get(test);
            if (recorded == null || current == null || !recorded.equals(current)) {
                affected.add(test);
            }
        }
        return new Selection(affected, testSources.size(), false, false);
    }

    /**
     * Records the current hashes of every test after a fully green run, so the
     * next run can skip what a change cannot reach. Skipped tests keep their
     * existing entry (their hash did not change, which is why they were
     * skipped), so writing them again is a no-op.
     */
    public static void record(File projectRoot, List<Path> mainSources,
                              List<Path> testSources, List<Path> resourceDirs) {
        if (testSources == null || testSources.isEmpty()) {
            return;
        }
        List<Path> all = new ArrayList<>(mainSources == null ? Collections.emptyList() : mainSources);
        all.addAll(testSources);
        Map<Path, String> hashes = SourceDependencies.effectiveHashes(all);

        Map<String, String> tests = new TreeMap<>();
        for (Path test : testSources) {
            String hash = hashes.get(test);
            if (hash != null) {
                tests.put(relativeKey(projectRoot, test), hash);
            }
        }

        StringBuilder body = new StringBuilder();
        body.append("# forge test selection state - generated, do not edit (AGENT.md section 21)\n");
        body.append("schema = \"").append(SCHEMA).append("\"\n");
        body.append("resource_hash = \"").append(resourceHash(resourceDirs)).append("\"\n\n");
        body.append("[tests]\n");
        for (Map.Entry<String, String> e : tests.entrySet()) {
            body.append('"').append(escape(e.getKey())).append("\" = \"")
                .append(e.getValue()).append("\"\n");
        }

        Path statePath = statePath(projectRoot);
        try {
            Files.createDirectories(statePath.getParent());
            Path tmp = statePath.resolveSibling(STATE_FILE + ".tmp");
            Files.write(tmp, body.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // State is derived: failing to persist it only costs a full re-run
            // next time, so it must never fail the build.
        }
    }

    /**
     * Reads the recorded test hashes plus the resource hash, or null when there
     * is no usable state (missing, empty or corrupt) — always the safe answer.
     */
    private static Map<String, String> readState(File projectRoot) {
        Path statePath = statePath(projectRoot);
        if (!Files.isRegularFile(statePath)) {
            return null;
        }
        try {
            Config cfg = new TomlParser().parse(statePath, FileNotFoundAction.THROW_ERROR);
            Object schema = cfg.get("schema");
            if (!SCHEMA.equals(String.valueOf(schema))) {
                return null;
            }
            Map<String, String> out = new LinkedHashMap<>();
            Object resources = cfg.get("resource_hash");
            if (resources != null) {
                out.put("resource_hash", String.valueOf(resources));
            }
            UnmodifiableConfig tests = cfg.get("tests");
            if (tests != null) {
                for (UnmodifiableConfig.Entry e : tests.entrySet()) {
                    // Cast to Object: Entry.getValue() is generic and would
                    // otherwise let String.valueOf bind to valueOf(char[]).
                    out.put(e.getKey(), String.valueOf((Object) e.getValue()));
                }
            }
            return out;
        } catch (Exception e) {
            // Corrupt or unreadable state: fall back to running everything.
            return null;
        }
    }

    private static Path statePath(File projectRoot) {
        return new File(projectRoot, STATE_DIR + File.separator + STATE_FILE).toPath();
    }

    /** Project-relative POSIX key, or the absolute path when outside the project. */
    private static String relativeKey(File projectRoot, Path file) {
        Path root = projectRoot.toPath().toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        try {
            return root.relativize(abs).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return abs.toString().replace(File.separatorChar, '/');
        }
    }

    /**
     * One hash over every resource file (relative path + content), so any
     * resource change re-runs all tests. Stable and portable: paths are
     * relative and sorted, and no timestamp enters the hash.
     */
    static String resourceHash(List<Path> resourceDirs) {
        Map<String, String> entries = new TreeMap<>();
        if (resourceDirs != null) {
            for (Path dir : resourceDirs) {
                if (dir == null || !Files.isDirectory(dir)) {
                    continue;
                }
                try {
                    Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .forEach(f -> entries.put(
                            dir.getFileName() + "/" + dir.relativize(f).toString().replace('\\', '/'),
                            sha256(readBytes(f))));
                } catch (IOException ignored) {
                    // An unreadable resource tree cannot be hashed; the caller
                    // re-runs everything because the hash will differ.
                }
            }
        }
        MessageDigest digest = sha256Digest();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            update(digest, e.getKey());
            update(digest, e.getValue());
        }
        return "sha256:" + hex(digest.digest());
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256(byte[] data) {
        MessageDigest digest = sha256Digest();
        digest.update(data);
        return hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
