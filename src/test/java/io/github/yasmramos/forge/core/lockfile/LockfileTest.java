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
        io.github.yasmramos.forge.cli.error.ForgeException e =
            assertThrows(io.github.yasmramos.forge.cli.error.ForgeException.class,
                () -> reread.verifyAgainst(toml));
        assertEquals("FORGE-LCK-0003", e.getCode());
        assertTrue(e.getError().getHowToFix().contains("forge lock refresh"),
            "error must be actionable");
    }

    @Test
    void missingLockExplainsHowToFix(@TempDir File root) {
        io.github.yasmramos.forge.cli.error.ForgeException e =
            assertThrows(io.github.yasmramos.forge.cli.error.ForgeException.class,
                () -> Lockfile.read(root));
        assertEquals("FORGE-LCK-0004", e.getCode());
    }

    @Test
    void canonicalSerializationIsByteStable(@TempDir File root) throws Exception {
        File toml = new File(root, "forge.toml");
        Files.write(toml.toPath(), "cfg".getBytes(StandardCharsets.UTF_8));
        String hash = Lockfile.hashOf(toml);
        // Same content, different insertion order -> identical bytes (diff-noise invariant).
        Map<String, Lockfile.LockedDependency> m1 = sample();
        Map<String, Lockfile.LockedDependency> m2 = new java.util.LinkedHashMap<>();
        for (String k : new java.util.TreeSet<>(m1.keySet())) {
            m2.put(k, m1.get(k));
        }
        new Lockfile(hash, m1).write(root);
        byte[] first = Files.readAllBytes(new File(root, Lockfile.FILE_NAME).toPath());
        new Lockfile(hash, m2).write(root);
        byte[] second = Files.readAllBytes(new File(root, Lockfile.FILE_NAME).toPath());
        assertArrayEquals(first, second, "canonical serialization must be deterministic");
    }

    @Test
    void tamperedLockFailsIntegrityCheck(@TempDir File root) throws Exception {
        File toml = new File(root, "forge.toml");
        Files.write(toml.toPath(), "cfg".getBytes(StandardCharsets.UTF_8));
        new Lockfile(Lockfile.hashOf(toml), sample()).write(root);
        File lock = new File(root, Lockfile.FILE_NAME);
        // Manual edit of a version line without updating content_hash -> LCK-0001.
        String body = new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8);
        body = body.replace("\"2.0.7\"", "\"9.9.9\"");
        Files.write(lock.toPath(), body.getBytes(StandardCharsets.UTF_8));
        io.github.yasmramos.forge.cli.error.ForgeException e =
            assertThrows(io.github.yasmramos.forge.cli.error.ForgeException.class,
                () -> Lockfile.read(root));
        assertEquals("FORGE-LCK-0001", e.getCode());
    }
}
