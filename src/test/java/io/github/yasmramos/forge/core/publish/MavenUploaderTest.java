package io.github.yasmramos.forge.core.publish;

import com.sun.net.httpserver.HttpServer;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenUploader} against a real in-process HTTP server: the
 * uploader must lay artifacts out in Maven repository layout, send checksums,
 * attach Basic auth, and fail loudly on a rejected upload (AGENT.md section 6).
 */
class MavenUploaderTest {

    private HttpServer server;
    private final Map<String, byte[]> received = new ConcurrentHashMap<>();
    private final List<String> authHeaders = new ArrayList<>();

    private String startServer(int responseStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authHeaders.add(auth);
            }
            if (responseStatus >= 200 && responseStatus < 300) {
                received.put(path, exchange.getRequestBody().readAllBytes());
            }
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void uploadsArtifactsAndChecksumsInMavenLayout() throws Exception {
        String base = startServer(201);
        Path root = Files.createTempDirectory("upload");
        File jar = Files.write(root.resolve("demo-1.0.jar"), "jar-bytes".getBytes(StandardCharsets.UTF_8)).toFile();
        File pom = Files.write(root.resolve("demo-1.0.pom"), "<project/>".getBytes(StandardCharsets.UTF_8)).toFile();

        int count = new MavenUploader(base, null)
            .upload("io.example", "demo", "1.0", java.util.Arrays.asList(jar, pom));

        assertEquals(2, count);
        String prefix = "/io/example/demo/1.0/";
        assertTrue(received.containsKey(prefix + "demo-1.0.jar"));
        assertTrue(received.containsKey(prefix + "demo-1.0.pom"));
        assertTrue(received.containsKey(prefix + "demo-1.0.jar.sha256"));
        assertTrue(received.containsKey(prefix + "demo-1.0.jar.md5"));
        assertTrue(received.containsKey(prefix + "demo-1.0.jar.sha1"));
        assertTrue(received.containsKey(prefix + "demo-1.0.jar.sha512"));

        // The sha256 file holds the hex digest plus a newline.
        byte[] sha256 = received.get(prefix + "demo-1.0.jar.sha256");
        String expected = toHex(MessageDigest.getInstance("SHA-256")
            .digest("jar-bytes".getBytes(StandardCharsets.UTF_8))) + "\n";
        assertEquals(expected, new String(sha256, StandardCharsets.UTF_8));
    }

    @Test
    void sendsBasicAuthWhenCredentialsProvided() throws Exception {
        String base = startServer(200);
        Path root = Files.createTempDirectory("upload");
        File jar = Files.write(root.resolve("demo-1.0.jar"), "x".getBytes(StandardCharsets.UTF_8)).toFile();

        new MavenUploader(base, new Credentials.Entry("deployer", "s3cr3t"))
            .upload("g", "a", "1", java.util.Collections.singletonList(jar));

        assertTrue(authHeaders.stream().anyMatch(h -> h.startsWith("Basic ")));
    }

    @Test
    void failsWithPub0006WhenTheRepositoryRejectsTheUpload() throws Exception {
        String base = startServer(403);
        Path root = Files.createTempDirectory("upload");
        File jar = Files.write(root.resolve("demo-1.0.jar"), "x".getBytes(StandardCharsets.UTF_8)).toFile();

        ForgeException ex = assertThrows(ForgeException.class, () -> new MavenUploader(base, null)
            .upload("g", "a", "1", java.util.Collections.singletonList(jar)));
        assertEquals(ErrorCode.PUB_0006.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("403"));
    }

    @Test
    void rejectsAnEmptyRepositoryUrl() {
        ForgeException ex = assertThrows(ForgeException.class, () -> new MavenUploader("", null));
        assertEquals(ErrorCode.PUB_0005.getCode(), ex.getCode());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
