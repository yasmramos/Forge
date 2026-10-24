package io.github.yasmramos.forge.core.lockfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for forge.lock semantics (AGENT.md decision 7). */
class LockfileTest {

    private Map<String, Lockfile.LockedDependency> sample() {
        Map<String, Lockfile.LockedDependency> m = new LinkedHashMap<>();
        m.put("org.slf4j:slf4j-api", new Lockfile.LockedDependency(
            "org.slf4j:slf4j-api", "2.0.7", "aaa", "https://repo1.maven.org/maven2"));
        m.put("ch.qos.logback:logback-classic", new Lockfile.LockedDependency(
            "ch.qos.logback:logback-classic", "1.4.11", "bbb", "https://repo1.maven.org/maven2"));
        return m;
    }

    @Test
    void writeReadRoundTrip(@TempDir File root) throws Exception {
        File toml = new File(root, "forge.toml");
        Files.write(toml.toPath(), "forge = \"0.1\"".getBytes(StandardCharsets.UTF_8));
        String hash = Lockfile.hashOf(toml);

        new Lockfile(hash, sample()).write(root);
        assertTrue(new File(root, Lockfile.FILE_NAME).isFile());

        Lockfile read = Lockfile.read(root);
        assertEquals(hash, read.getConfigHash());
        assertEquals(2, read.getDependencies().size());
        assertEquals("2.0.7", read.getDependencies().get("org.slf4j:slf4j-api").version);
        read.verifyAgainst(toml); // must not throw
    }

    @Test
    void staleLockFailsTheBuild(@TempDir File root) throws Exception {
        File toml = new File(root, "forge.toml");
        Files.write(toml.toPath(), "old config".getBytes(StandardCharsets.UTF_8));
        Lockfile lock = new Lockfile(Lockfile.hashOf(toml), sample());
        lock.write(root);

        // Configuration changes without refreshing the lock -> verification fails.
        Files.write(toml.toPath(), "new config".getBytes(StandardCharsets.UTF_8));
        Lockfile reread = Lockfile.read(root);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> reread.verifyAgainst(toml));
        assertTrue(e.getMessage().contains("stale"), e.getMessage());
        assertTrue(e.getMessage().contains("forge update"), "error must be actionable");
    }

    @Test
    void missingLockExplainsHowToFix(@TempDir File root) {
        Exception e = assertThrows(Exception.class, () -> Lockfile.read(root));
        assertTrue(e.getMessage().contains("forge update"), e.getMessage());
    }
}
