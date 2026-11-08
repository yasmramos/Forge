package io.github.yasmramos.forge.core.task;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The parts of the build environment that a task cache key must cover, beyond
 * the task's own inputs (AGENT.md section 3): the toolchain hash and the Forge
 * version hash, plus the identity of every plugin that affects the task.
 *
 * <p>Without this, switching JDK or upgrading Forge would reuse entries built
 * with a different compiler or core, serving stale bytecode as a cache hit -
 * exactly the silent failure the cache exists to prevent. Plugin identity is
 * folded in the same way, so a plugin bump invalidates the tasks it touches.</p>
 *
 * <p>Everything here is derived deterministically from the running JVM and the
 * Forge build, so it is stable across machines and checkouts for the same
 * environment.</p>
 */
public final class BuildEnvironment {

    private final String toolchainHash;
    private final String forgeVersionHash;
    private final String pluginsHash;

    BuildEnvironment(String toolchainHash, String forgeVersionHash, String pluginsHash) {
        this.toolchainHash = toolchainHash;
        this.forgeVersionHash = forgeVersionHash;
        this.pluginsHash = pluginsHash;
    }

    /** Captures the environment of the running build. */
    public static BuildEnvironment current() {
        return new BuildEnvironment(
            toolchainHash(),
            forgeVersionHash(),
            pluginsHash(java.util.Collections.emptyList()));
    }

    /**
     * Captures the environment of the running build for a project declaring the
     * given plugins (id -&gt; coordinate or version, as read from
     * {@code forge.toml}). Toolchain and Forge version come from the running
     * build; plugin identity is folded in so bumping one invalidates the tasks
     * it affects (AGENT.md sections 3 and 4bis).
     */
    public static BuildEnvironment forProject(Map<String, ?> plugins) {
        List<String> idVersions = new ArrayList<>();
        if (plugins != null) {
            for (Map.Entry<String, ?> e : plugins.entrySet()) {
                idVersions.add(e.getKey() + ":" + e.getValue());
            }
        }
        return new BuildEnvironment(toolchainHash(), forgeVersionHash(), pluginsHash(idVersions));
    }

    /**
     * Captures the environment for the plugins actually loaded, by their
     * {@code id:version} identity (AGENT.md section 3: the cache key folds in
     * the versions of every plugin that affects the task). This is the loaded
     * truth, not the declared coordinate, so it stays stable whether the plugin
     * was resolved from a coordinate or a local jar.
     */
    public static BuildEnvironment forPlugins(List<String> pluginIdVersions) {
        return new BuildEnvironment(toolchainHash(), forgeVersionHash(),
            pluginsHash(pluginIdVersions == null ? new ArrayList<>() : pluginIdVersions));
    }

    public String getToolchainHash() {
        return toolchainHash;
    }

    public String getForgeVersionHash() {
        return forgeVersionHash;
    }

    public String getPluginsHash() {
        return pluginsHash;
    }

    /**
     * Hash of the toolchain that compiles and runs the build: the JVM
     * implementation and version, and the vendor. This is what changes when a
     * different JDK is selected, and it is what must invalidate cached outputs.
     */
    static String toolchainHash() {
        return toolchainHash(
            System.getProperty("java.vm.name"),
            System.getProperty("java.version"),
            System.getProperty("java.vendor"));
    }

    static String toolchainHash(String vmName, String version, String vendor) {
        MessageDigest digest = TaskInput.sha256();
        update(digest, "toolchain");
        update(digest, vmName);
        update(digest, version);
        update(digest, vendor);
        return TaskInput.hex(digest.digest());
    }

    /**
     * Hash of the running Forge version, read from the packaged
     * {@code pom.properties} (present in the built jar) and falling back to the
     * package implementation version, then to a stable "unknown" marker. An
     * unknown version still hashes deterministically, so caching stays correct
     * even when run from a classpath that carries no metadata.
     */
    static String forgeVersionHash() {
        return forgeVersionHash(forgeVersion());
    }

    static String forgeVersionHash(String version) {
        MessageDigest digest = TaskInput.sha256();
        update(digest, "forge");
        update(digest, version);
        return TaskInput.hex(digest.digest());
    }

    static String forgeVersion() {
        Properties props = new Properties();
        try (InputStream in = BuildEnvironment.class.getResourceAsStream(
                "/META-INF/maven/io.github.yasmramos/forge/pom.properties")) {
            if (in != null) {
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // Fall through to the next source of version information.
        }
        Package pkg = BuildEnvironment.class.getPackage();
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "unknown";
    }

    /**
     * Hash of the plugins that affect the build, given as "id:version" pairs.
     * Sorted, so declaration order does not affect the key. Returns a stable
     * empty marker when there are none (AGENT.md section 4bis: a plugin bump
     * must invalidate the tasks it affects).
     */
    static String pluginsHash(List<String> idVersions) {
        MessageDigest digest = TaskInput.sha256();
        update(digest, "plugins");
        List<String> sorted = new ArrayList<>(idVersions);
        java.util.Collections.sort(sorted);
        for (String p : sorted) {
            update(digest, p);
        }
        return TaskInput.hex(digest.digest());
    }

    private static void update(MessageDigest digest, String s) {
        digest.update((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
