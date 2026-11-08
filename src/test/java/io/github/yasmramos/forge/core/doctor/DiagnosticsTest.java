package io.github.yasmramos.forge.core.doctor;

import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code forge doctor} checks (AGENT.md section 10).
 * The checks are pure over {@link Diagnostics.Environment}, so no real JDK,
 * network, or project layout is needed.
 */
class DiagnosticsTest {

    private static Diagnostics.Check find(Diagnostics.Report report, String name) {
        return report.getChecks().stream()
            .filter(c -> c.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no check named " + name));
    }

    private static Diagnostics.Environment healthyEnv(File root) throws Exception {
        Diagnostics.Environment env = new Diagnostics.Environment();
        env.javaVersion = "11.0.25";
        env.javaVendor = "Eclipse Adoptium";
        env.javaHome = System.getProperty("java.home");
        env.hasCompiler = true;
        env.processors = 4;
        env.maxMemoryMb = 2048;
        env.projectRoot = root;
        env.config = new ProjectConfig();
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        env.config.setRepositories(repos);
        java.nio.file.Files.write(new File(root, "forge.toml").toPath(),
            "forge = \"0.1\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return env;
    }

    @Test
    void reportsEnvironmentChecksEvenWithoutAProject() {
        Diagnostics.Environment env = new Diagnostics.Environment();
        env.javaVersion = "17.0.9";
        env.javaVendor = "Oracle";
        env.hasCompiler = true;

        Diagnostics.Report report = Diagnostics.run(env);

        assertEquals(Diagnostics.Severity.OK, find(report, "Java runtime").getSeverity());
        assertEquals(Diagnostics.Severity.OK, find(report, "Full JDK").getSeverity());
        assertFalse(report.hasFailures());
    }

    @Test
    void missingCompilerFailsAsEnvironmentProblem() {
        Diagnostics.Environment env = new Diagnostics.Environment();
        env.javaVersion = "11.0.25";
        env.hasCompiler = false;

        Diagnostics.Report report = Diagnostics.run(env);

        assertEquals(Diagnostics.Severity.FAIL, find(report, "Full JDK").getSeverity());
        assertTrue(report.hasFailures());
    }

    @Test
    void tooOldJavaFails() {
        Diagnostics.Environment env = new Diagnostics.Environment();
        env.javaVersion = "8.0.392";
        env.hasCompiler = true;

        Diagnostics.Report report = Diagnostics.run(env);

        assertEquals(Diagnostics.Severity.FAIL, find(report, "Java runtime").getSeverity());
    }

    @Test
    void missingLockfileIsAWarningNotAFailure(@org.junit.jupiter.api.io.TempDir File root) throws Exception {
        Diagnostics.Report report = Diagnostics.run(healthyEnv(root));

        Diagnostics.Check lock = find(report, "forge.lock");
        assertEquals(Diagnostics.Severity.WARN, lock.getSeverity());
        assertFalse(report.hasFailures());
    }

    @Test
    void missingModuleForgeTomlFails(@org.junit.jupiter.api.io.TempDir File root) throws Exception {
        Diagnostics.Environment env = healthyEnv(root);
        Map<String, String> modules = new LinkedHashMap<>();
        modules.put("core", "core");
        modules.put("app", "app");
        env.config.setModules(modules);
        assertTrue(new File(root, "core").mkdirs());

        Diagnostics.Report report = Diagnostics.run(env);

        Diagnostics.Check modulesCheck = find(report, "Modules");
        assertEquals(Diagnostics.Severity.FAIL, modulesCheck.getSeverity());
        assertTrue(modulesCheck.getDetail().contains("app"));
        assertTrue(report.hasFailures());
    }

    @Test
    void toolchainMismatchWarns(@org.junit.jupiter.api.io.TempDir File root) throws Exception {
        Diagnostics.Environment env = healthyEnv(root);
        env.requiredJdkVersion = "17";

        Diagnostics.Report report = Diagnostics.run(env);

        assertEquals(Diagnostics.Severity.WARN, find(report, "Toolchain").getSeverity());
    }

    @Test
    void toolchainMatchIsOk(@org.junit.jupiter.api.io.TempDir File root) throws Exception {
        Diagnostics.Environment env = healthyEnv(root);
        env.requiredJdkVersion = "11";

        Diagnostics.Report report = Diagnostics.run(env);

        assertEquals(Diagnostics.Severity.OK, find(report, "Toolchain").getSeverity());
    }

    @Test
    void majorOfUnderstandsLegacyAndModernSchemes() {
        assertEquals(8, Diagnostics.majorOf("1.8.0_382"));
        assertEquals(11, Diagnostics.majorOf("11.0.25"));
        assertEquals(17, Diagnostics.majorOf("17"));
        assertEquals(21, Diagnostics.majorOf("21.0.1+12"));
        assertEquals(0, Diagnostics.majorOf("not-a-version"));
        assertEquals(0, Diagnostics.majorOf(null));
    }
}
