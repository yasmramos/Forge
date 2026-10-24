package io.github.yasmramos.forge.core.task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A task input: anything that can affect the task result (AGENT.md decision 1).
 * Every input must be deterministically hashable; an input that cannot be
 * hashed is a design error, not an exception. Timestamps are never used.
 */
public abstract class TaskInput {

    private final String name;

    protected TaskInput(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** Returns a deterministic SHA-256 hex digest of this input's content. */
    public abstract String hash();

    /** File(s)-input: hashes file contents recursively, sorted by relative path. */
    public static class FilesInput extends TaskInput {
        private final List<File> roots;

        public FilesInput(String name, List<File> roots) {
            super(name);
            this.roots = roots;
        }

        @Override
        public String hash() {
            MessageDigest digest = sha256();
            // Sorted traversal for determinism regardless of filesystem order.
            Map<String, File> sorted = new TreeMap<>();
            for (File root : roots) {
                collect(root, root, sorted);
            }
            for (Map.Entry<String, File> e : sorted.entrySet()) {
                update(digest, e.getKey());          // relative path first
                try {
                    update(digest, Files.readAllBytes(e.getValue().toPath()));
                } catch (IOException ex) {
                    throw new IllegalStateException(
                        "Cannot hash input file " + e.getValue(), ex);
                }
            }
            return hex(digest.digest());
        }
    }

    /** Property-input: hashes a string value (config property, version, etc.). */
    public static class ValueInput extends TaskInput {
        private final String value;

        public ValueInput(String name, String value) {
            super(name);
            this.value = value == null ? "" : value;
        }

        @Override
        public String hash() {
            MessageDigest digest = sha256();
            update(digest, getName());
            update(digest, value);
            return hex(digest.digest());
        }
    }

    /** Environment-input: declared env vars only (AGENT.md decision 24). */
    public static class EnvInput extends TaskInput {
        private final String variable;

        public EnvInput(String variable) {
            super(variable);
            this.variable = variable;
        }

        @Override
        public String hash() {
            MessageDigest digest = sha256();
            update(digest, "env:" + variable);
            update(digest, System.getenv(variable));
            return hex(digest.digest());
        }
    }

    private static void collect(File root, File base, Map<String, File> out) {
        if (!root.exists()) {
            return;
        }
        if (root.isFile()) {
            out.put(base.toPath().relativize(root.toPath()).toString().replace('\\', '/'), root);
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                collect(child, base, out);
            }
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void update(MessageDigest digest, String s) {
        digest.update((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void update(MessageDigest digest, byte[] b) {
        if (b != null) {
            digest.update(b);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
