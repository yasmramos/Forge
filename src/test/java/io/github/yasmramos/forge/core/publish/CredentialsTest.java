package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@code ~/.forge/credentials.toml} loader (AGENT.md section 6). */
class CredentialsTest {

    @Test
    void loadsPerRepositoryCredentials(@TempDir File root) throws Exception {
        File file = new File(root, Credentials.FILE_NAME);
        Files.write(file.toPath(),
            ("[central]\nusername = \"deployer\"\npassword = \"s3cr3t\"\n"
             + "[ossrh-snapshots]\nusername = \"snap\"\npassword = \"pw\"\n")
                .getBytes(StandardCharsets.UTF_8));

        Credentials creds = Credentials.load(file);

        assertNotNull(creds.forRepository("central"));
        assertEquals("deployer", creds.forRepository("central").getUsername());
        assertEquals("s3cr3t", creds.forRepository("central").getPassword());
        assertEquals("snap", creds.forRepository("ossrh-snapshots").getUsername());
    }

    @Test
    void missingFileYieldsAnEmptySet(@TempDir File root) {
        Credentials creds = Credentials.load(new File(root, "nope.toml"));
        assertTrue(creds.isEmpty());
        assertNull(creds.forRepository("central"));
    }

    @Test
    void unknownRepositoryHasNoCredentials(@TempDir File root) throws Exception {
        File file = new File(root, Credentials.FILE_NAME);
        Files.write(file.toPath(), "[central]\nusername = \"u\"\npassword = \"p\"\n"
            .getBytes(StandardCharsets.UTF_8));

        assertNull(Credentials.load(file).forRepository("other"));
    }

    @Test
    void malformedFileFailsWithPub0007(@TempDir File root) throws Exception {
        File file = new File(root, Credentials.FILE_NAME);
        Files.write(file.toPath(), "this is not = = toml [".getBytes(StandardCharsets.UTF_8));

        ForgeException ex = assertThrows(ForgeException.class, () -> Credentials.load(file));
        assertEquals(ErrorCode.PUB_0007.getCode(), ex.getCode());
    }

    @Test
    void ignoresEntriesWithoutBothUsernameAndPassword(@TempDir File root) throws Exception {
        File file = new File(root, Credentials.FILE_NAME);
        Files.write(file.toPath(), "[central]\nusername = \"u\"\n"
            .getBytes(StandardCharsets.UTF_8));

        assertNull(Credentials.load(file).forRepository("central"));
    }
}
