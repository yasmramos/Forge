package io.github.yasmramos.forge.core.doctor;

import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Environment and project diagnostics behind {@code forge doctor}
 * (AGENT.md section 10).
 *
 * <p>The checks are pure with respect to their {@link Environment} input, so
 * they can be exercised without a real JDK, network, or project layout. The
 * command layer fills an {@code Environment} from the running system and
 * renders the resulting {@link Report}.</p>
 */
public final class Diagnostics {

    /** Severity of a single check, ordered from healthy to blocking. */
    public enum Severity { OK, WARN, FAIL }

    /** One diagnostic result: what was checked, how it went, and how to fix it. */
    public static final class Check {
        private final String name;
        private final Severity severity;
        private final String detail;
        private final String fix;

        Check(String name, Severity severity, String detail, String fix) {
            this.name = name;
            this.severity = severity;
            this.detail = detail;
            this.fix = fix;
        }

        public String getName() { return name; }
        public Severity getSeverity() { return severity; }
        public String getDetail() { return detail; }
        public String getFix() { return fix; }
    }

    /** Ordered list of checks; healthy when no check failed. */
    public static final class Report {
        private final List<Check> checks = new ArrayList<>();

        void add(String name, Severity severity, String detail, String fix) {
            checks.add(new Check(name, severity, detail, fix));
        }

        public List<Check> getChecks() {
            return Collections.unmodifiableList(checks);
        }

        public boolean hasFailures() {
            return checks.stream().anyMatch(c -> c.getSeverity() == Severity.FAIL);
        }

        public boolean hasWarnings() {
            return checks.stream().anyMatch(c -> c.getSeverity() == Severity.WARN);
        }
    }

    /** Everything the checks observe, so tests can supply synthetic values. */
    public static final class Environment {
        public String javaVersion = "";
        public String javaVendor = "";
        public String javaHome = "";
        public boolean hasCompiler;
        public int processors = 1;
        public long maxMemoryMb;
        public File projectRoot;
        public ProjectConfig config;
        /** Required JDK version from the [project] toolchain, or null when undeclared. */
        public String requiredJdkVersion;
        /** Lowest JDK Forge itself supports (source/target baseline). */
        public int minimumJdk = 11;
    }

    private Diagnostics() {
    }

    public static Report run(Environment env) {
        Report report = new Report();
        checkJava(report, env);
        checkJdk(report, env);
        checkJavaHome(report, env);
        checkMemory(report, env);
        if (env.config != null && env.projectRoot != null) {
            checkConfig(report, env);
            checkLockfile(report, env);
            checkRepositories(report, env);
            checkCache(report, env);
            checkToolchain(report, env);
            checkModules(report, env);
        }
        return report;
    }

    private static void checkJava(Report report, Environment env) {
        int major = majorOf(env.javaVersion);
        if (major == 0) {
            report.add("Java runtime", Severity.FAIL,
                "could not determine the Java version (java.version='" + env.javaVersion + "')",
                "Run Forge on a supported JDK " + env.minimumJdk + "+.");
            return;
        }
        if (major < env.minimumJdk) {
            report.add("Java runtime", Severity.FAIL,
                "running on Java " + major + " (" + env.javaVendor + "); Forge needs "
                    + env.minimumJdk + "+",
                "Install JDK " + env.minimumJdk + " or newer and point JAVA_HOME at it.");
            return;
        }
        report.add("Java runtime", Severity.OK,
            "Java " + major + " (" + env.javaVendor + "), " + env.processors
                + " processor(s)", null);
    }

    private static void checkJdk(Report report, Environment env) {
        if (env.hasCompiler) {
            report.add("Full JDK", Severity.OK,
                "javac is available (ToolProvider.getSystemJavaCompiler)", null);
        } else {
            report.add("Full JDK", Severity.FAIL,
                "no system Java compiler found; Forge is running on a JRE",
                "Install a full JDK (not just a JRE) and set JAVA_HOME to it.");
        }
    }

    private static void checkJavaHome(Report report, Environment env) {
        String home = env.javaHome;
        if (home == null || home.trim().isEmpty()) {
            report.add("JAVA_HOME", Severity.WARN, "JAVA_HOME is not set",
                "Set JAVA_HOME to a JDK " + env.minimumJdk + "+ installation.");
            return;
        }
        if (!new File(home).isDirectory()) {
            report.add("JAVA_HOME", Severity.WARN,
                "JAVA_HOME points at a directory that does not exist: " + home,
                "Fix JAVA_HOME to an existing JDK installation.");
            return;
        }
        report.add("JAVA_HOME", Severity.OK, home, null);
    }

    private static void checkMemory(Report report, Environment env) {
        if (env.maxMemoryMb > 0 && env.maxMemoryMb < 512) {
            report.add("Heap", Severity.WARN,
                "max heap is only " + env.maxMemoryMb + " MB",
                "Raise the heap (e.g. JAVA_OPTS=-Xmx1g) for larger builds.");
        } else if (env.maxMemoryMb > 0) {
            report.add("Heap", Severity.OK, "max heap " + env.maxMemoryMb + " MB", null);
        }
    }

    private static void checkConfig(Report report, Environment env) {
        File toml = new File(env.projectRoot, ConfigReader.CONFIG_FILE_NAME);
        if (toml.isFile()) {
            report.add("forge.toml", Severity.OK, toml.getAbsolutePath(), null);
        } else {
            report.add("forge.toml", Severity.FAIL,
                "no forge.toml in " + env.projectRoot.getAbsolutePath(),
                "Run 'forge init' to create a project.");
        }
    }

    private static void checkLockfile(Report report, Environment env) {
        File lockFile = new File(env.projectRoot, Lockfile.FILE_NAME);
        if (!lockFile.isFile()) {
            report.add("forge.lock", Severity.WARN, "missing",
                "Run 'forge lock refresh' and commit forge.lock so dependency sets are pinned.");
            return;
        }
        Lockfile lock;
        try {
            lock = Lockfile.read(env.projectRoot);
        } catch (ForgeException e) {
            report.add("forge.lock", Severity.FAIL,
                "unreadable: " + e.getError().getWhat(), e.getError().getHowToFix());
            return;
        } catch (Exception e) {
            report.add("forge.lock", Severity.FAIL,
                "could not read forge.lock: " + e.getMessage(),
                "Delete forge.lock and run 'forge lock refresh'.");
            return;
        }

        // Staleness is only checked here for single-module projects: the
        // multi-module lock hashes every module config (section 8bis) and is
        // validated by the build itself.
        if (env.config.getModules().isEmpty()) {
            try {
                lock.verifyAgainst(new File(env.projectRoot, ConfigReader.CONFIG_FILE_NAME));
            } catch (ForgeException e) {
                report.add("forge.lock", Severity.WARN,
                    "stale: " + e.getError().getWhat(), e.getError().getHowToFix());
                return;
            } catch (Exception e) {
                report.add("forge.lock", Severity.WARN,
                    "could not verify forge.lock: " + e.getMessage(),
                    "Run 'forge lock refresh'.");
                return;
            }
        }
        report.add("forge.lock", Severity.OK,
            lock.getDependencies().size() + " locked dependencies", null);
    }

    private static void checkRepositories(Report report, Environment env) {
        Map<String, String> repos = env.config.getRepositories();
        if (repos == null || repos.isEmpty()) {
            report.add("Repositories", Severity.WARN, "none configured",
                "Declare at least one repository under [repositories] in forge.toml.");
            return;
        }
        report.add("Repositories", Severity.OK,
            repos.size() + " configured: " + String.join(", ", repos.keySet()), null);
    }

    private static void checkCache(Report report, Environment env) {
        File cacheDir = new File(env.projectRoot, ".forge" + File.separator + "cache");
        if (cacheDir.isDirectory() && cacheDir.canWrite()) {
            report.add("Build cache", Severity.OK, cacheDir.getAbsolutePath(), null);
        } else if (cacheDir.isDirectory()) {
            report.add("Build cache", Severity.WARN,
                "not writable: " + cacheDir.getAbsolutePath(),
                "Fix permissions on .forge/cache so incremental builds can reuse outputs.");
        } else {
            report.add("Build cache", Severity.OK,
                "not created yet (populated on first build): " + cacheDir.getAbsolutePath(), null);
        }
    }

    private static void checkToolchain(Report report, Environment env) {
        String required = env.requiredJdkVersion;
        if (required == null || required.trim().isEmpty()) {
            report.add("Toolchain", Severity.OK,
                "no JDK version pinned in [project]; using the running JDK", null);
            return;
        }
        int requiredMajor = majorOf(required);
        int runningMajor = majorOf(env.javaVersion);
        if (requiredMajor != 0 && runningMajor != 0 && requiredMajor != runningMajor) {
            report.add("Toolchain", Severity.WARN,
                "forge.toml pins JDK " + required + " but Forge is running on Java " + runningMajor,
                "Run Forge with JDK " + required + ", or remove the pin to use the running JDK.");
            return;
        }
        report.add("Toolchain", Severity.OK, "JDK " + required + " matches the running JDK", null);
    }

    private static void checkModules(Report report, Environment env) {
        Map<String, String> modules = env.config.getModules();
        if (modules == null || modules.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : modules.entrySet()) {
            String dir = e.getValue() == null || e.getValue().isEmpty() ? e.getKey() : e.getValue();
            File moduleToml = new File(new File(env.projectRoot, dir), ConfigReader.CONFIG_FILE_NAME);
            if (!moduleToml.isFile()) {
                missing.add(dir);
            }
        }
        if (missing.isEmpty()) {
            report.add("Modules", Severity.OK,
                modules.size() + " module(s) each with a forge.toml", null);
        } else {
            report.add("Modules", Severity.FAIL,
                "module(s) without a forge.toml: " + String.join(", ", missing),
                "Create <module>/forge.toml, or fix the mapping in [project.modules] (FORGE-MMD-0002).");
        }
    }

    /** Major version of a JDK string ("11.0.20", "1.8.0_382", "17") or 0. */
    static int majorOf(String version) {
        if (version == null || version.trim().isEmpty()) {
            return 0;
        }
        String v = version.trim();
        int start = 0;
        while (start < v.length() && !Character.isDigit(v.charAt(start))) {
            start++;
        }
        v = v.substring(start);
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            int major = Integer.parseInt(v.substring(0, end));
            if (major == 1) {
                // Legacy "1.8" scheme: the real major is the second component.
                String rest = v.substring(end);
                int dot = rest.indexOf('.');
                if (dot >= 0) {
                    int s = dot + 1;
                    int e = s;
                    while (e < rest.length() && Character.isDigit(rest.charAt(e))) {
                        e++;
                    }
                    if (e > s) {
                        return Integer.parseInt(rest.substring(s, e));
                    }
                }
                return 8;
            }
            return major;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
