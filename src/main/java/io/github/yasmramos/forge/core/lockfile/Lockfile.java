package io.github.yasmramos.forge.core.lockfile;

import com.electronwill.nightconfig.core.Config;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * forge.lock: fully resolved dependency set with exact versions and checksums
 * (AGENT.md decision 7). Committed to version control, updated only by an
 * explicit command (forge update / forge lock refresh), never during a build.
 * A build fails when the config hash recorded in the lock does not match the
 * current configuration, forcing dependency updates to be a conscious act.
 */
public class Lockfile {

    public static final String FILE_NAME = "forge.lock";

    public static class LockedDependency {
        public final String coordinate; // group:artifact
        public final String version;    // exact resolved version
        public final String sha256;     // content checksum (mandatory)
        public final String repository; // source repo URL

        public LockedDependency(String coordinate, String version, String sha256, String repository) {
            this.coordinate = coordinate;
            this.version = version;
            this.sha256 = sha256;
            this.repository = repository;
        }
    }

    private final String configHash;
    private final Map<String, LockedDependency> dependencies;

    public Lockfile(String configHash, Map<String, LockedDependency> dependencies) {
        this.configHash = configHash;
        // Deterministic ordering by coordinate for diff-friendly output.
        this.dependencies = new LinkedHashMap<>();
        new ArrayList<>(dependencies.keySet()).stream().sorted()
            .forEach(k -> this.dependencies.put(k, dependencies.get(k)));
    }

    public String getConfigHash() { return configHash; }
    public Map<String, LockedDependency> getDependencies() { return dependencies; }

    /** SHA-256 over the raw forge.toml bytes; ties the lock to one exact config. */
    public static String hashOf(File forgeToml) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(forgeToml.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public void write(File projectRoot) throws Exception {
        Config cfg = Config.inMemory();
        cfg.set("lock.schema", "0.1");
        cfg.set("lock.config_hash", configHash);
        List<Object> deps = new ArrayList<>();
        for (LockedDependency d : dependencies.values()) {
            Config entry = Config.inMemory();
            entry.set("coordinate", d.coordinate);
            entry.set("version", d.version);
            entry.set("sha256", d.sha256);
            entry.set("repository", d.repository);
            deps.add(entry);
        }
        cfg.set("dependency", deps);
        File out = new File(projectRoot, FILE_NAME);
        try (java.io.OutputStream os = Files.newOutputStream(out.toPath())) {
            com.electronwill.nightconfig.toml.TomlFormat.instance()
                .createWriter().write(cfg, os);
        }
    }

    public static Lockfile read(File projectRoot) throws Exception {
        File f = new File(projectRoot, FILE_NAME);
        if (!f.isFile()) {
            throw new IllegalStateException("Missing " + FILE_NAME
                + ". Fix: run 'forge update' to generate it.");
        }
        Config cfg;
        try (java.io.InputStream is = Files.newInputStream(f.toPath())) {
            cfg = new com.electronwill.nightconfig.toml.TomlParser().parse(is);
        }
        String hash = (String) cfg.get("lock.config_hash");
        Map<String, LockedDependency> deps = new LinkedHashMap<>();
        for (Object o : cfg.getOrElse("dependency", new ArrayList<>())) {
            Config c = (Config) o;
            LockedDependency d = new LockedDependency(
                (String) c.get("coordinate"), (String) c.get("version"),
                (String) c.get("sha256"), (String) c.get("repository"));
            deps.put(d.coordinate, d);
        }
        return new Lockfile(hash, deps);
    }

    /** Fails the build when the lock was produced from a different configuration. */
    public void verifyAgainst(File forgeToml) throws Exception {
        String current = hashOf(forgeToml);
        if (!current.equals(configHash)) {
            throw new IllegalStateException(
                "forge.lock is stale: it was generated from a different forge.toml.\n"
              + "What happened: configuration changed after the last lock refresh.\n"
              + "Why: builds must use pinned, consciously-updated dependencies.\n"
              + "Fix: run 'forge update' to refresh the lock, then commit it.");
        }
    }
}
