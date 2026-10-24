package io.github.yasmramos.forge.core.resolver;

import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the AGENT.md pipeline: resolve with Aether -> enforce
 * conflict policy (section 2bis) -> emit canonical lockfile (section 7bis).
 * These hit Maven Central, mirroring AetherIntegrationTest.
 */
class LockfileGeneratorTest {

    private File projectWithToml(File dir, String toml) throws Exception {
        Files.write(new File(dir, "forge.toml").toPath(),
                toml.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    void generatesLockWithExactVersionsAndChecksums(@TempDir File tmp) throws Exception {
        File root = projectWithToml(tmp, String.join("\n",
                "forge = \"0.1\"",
                "[project]",
                "name = \"lockgen\"",
                "version = \"1.0.0\"",
                "[repositories]",
                "central = \"https://repo1.maven.org/maven2\"",
                "[dependencies.compile]",
                "\"org.slf4j:slf4j-api\" = \"2.0.7\"",
                ""));
        ProjectConfig config = ConfigReader.loadConfig(new File(root, "forge.toml"));

        File cache = new File(tmp, "cache");
        Lockfile lock = new LockfileGenerator(cache, config.getRepositories())
                .generate(root, config);

        // slf4j-api has no compile-scope transitives: exactly one locked entry.
        assertEquals(1, lock.getDependencies().size());
        Lockfile.LockedDependency d = lock.getDependencies().get("org.slf4j:slf4j-api");
        assertNotNull(d);
        assertEquals("2.0.7", d.version);
        assertEquals(64, d.sha256.length(), "sha256 hex digest expected");
        assertFalse(d.sha256.isEmpty(), "checksum is mandatory (AGENT.md section 18)");

        // Round-trip through the canonical writer/reader keeps bytes stable.
        lock.write(root);
        Lockfile reloaded = Lockfile.read(root);
        assertEquals(lock.getConfigHash(), reloaded.getConfigHash());
        assertEquals(lock.getDependencies().keySet(), reloaded.getDependencies().keySet());
        assertEquals(d.sha256, reloaded.getDependencies().get("org.slf4j:slf4j-api").sha256);

        // The lock validates against the very forge.toml that produced it.
        reloaded.verifyAgainst(new File(root, "forge.toml"));
    }

    @Test
    void resolutionsSectionPinsConflictingTransitiveVersions(@TempDir File tmp) throws Exception {
        // logback-classic 1.4.11 pulls slf4j-api 2.0.x; we pin an explicit version
        // via [resolutions] and expect the lock to record exactly the pinned one.
        File root = projectWithToml(tmp, String.join("\n",
                "forge = \"0.1\"",
                "[project]",
                "name = \"pinned\"",
                "version = \"1.0.0\"",
                "[repositories]",
                "central = \"https://repo1.maven.org/maven2\"",
                "[dependencies.compile]",
                "\"ch.qos.logback:logback-classic\" = \"1.4.11\"",
                "[resolutions]",
                "\"org.slf4j:slf4j-api\" = \"2.0.7\"",
                ""));
        ProjectConfig config = ConfigReader.loadConfig(new File(root, "forge.toml"));
        assertEquals("2.0.7", config.getResolutions().get("org.slf4j:slf4j-api"),
                "[resolutions] must be parsed from forge.toml (AGENT.md section 2bis)");

        File cache = new File(tmp, "cache");
        Lockfile lock = new LockfileGenerator(cache, config.getRepositories())
                .generate(root, config);

        Lockfile.LockedDependency slf4j = lock.getDependencies().get("org.slf4j:slf4j-api");
        assertNotNull(slf4j, "transitive dependency must appear in the lock");
        assertEquals("2.0.7", slf4j.version, "forced resolution wins over graph selection");
        assertTrue(lock.getDependencies().containsKey("ch.qos.logback:logback-classic"));
    }

    @Test
    void emptyDependencySetProducesValidEmptyLock(@TempDir File tmp) throws Exception {
        File root = projectWithToml(tmp, String.join("\n",
                "forge = \"0.1\"",
                "[project]",
                "name = \"bare\"",
                "version = \"0.0.1\"",
                ""));
        ProjectConfig config = ConfigReader.loadConfig(new File(root, "forge.toml"));

        Lockfile lock = new LockfileGenerator(new File(tmp, "cache"), config.getRepositories())
                .generate(root, config);
        assertTrue(lock.getDependencies().isEmpty());

        lock.write(root);
        Lockfile reloaded = Lockfile.read(root);
        reloaded.verifyAgainst(new File(root, "forge.toml"));
    }

    @Test
    void unresolvableCoordinateFailsWithActionableMessage(@TempDir File tmp) throws Exception {
        File root = projectWithToml(tmp, String.join("\n",
                "forge = \"0.1\"",
                "[project]",
                "name = \"broken\"",
                "version = \"1.0.0\"",
                "[repositories]",
                "central = \"https://repo1.maven.org/maven2\"",
                "[dependencies.compile]",
                "\"io.forge.nonexistent:missing-artifact\" = \"9.9.9\"",
                ""));
        ProjectConfig config = ConfigReader.loadConfig(new File(root, "forge.toml"));

        AetherDependencyResolver.ResolutionFailedException ex = assertThrows(
                AetherDependencyResolver.ResolutionFailedException.class,
                () -> new LockfileGenerator(new File(tmp, "cache"), config.getRepositories())
                        .generate(root, config));
        assertTrue(ex.getMessage().contains("io.forge.nonexistent:missing-artifact"),
                "resolution errors must name the offending coordinate");
    }
}
