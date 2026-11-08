package io.github.yasmramos.forge.core.cache;

import com.sun.net.httpserver.HttpServer;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskInput;
import io.github.yasmramos.forge.core.task.TaskOutput;
import io.github.yasmramos.forge.core.task.TaskResult;
import io.github.yasmramos.forge.core.task.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the remote build cache against a real HTTP server (AGENT.md section 3).
 * No mocks: a JDK {@code HttpServer} speaks the wire protocol, so the client's
 * request/response handling, content addressing, local seeding and
 * end-of-build upload are exercised end to end.
 */
class RemoteCacheTest {

    /** A minimal in-process cache server: content-addressed, immutable. */
    private HttpServer server;
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicInteger puts = new AtomicInteger();
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forge", exchange -> {
            String hash = exchange.getRequestURI().getPath().substring("/forge/".length());
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                gets.incrementAndGet();
                byte[] blob = blobs.get(hash);
                if (blob == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, blob.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(blob);
                    }
                }
            } else if ("PUT".equals(method)) {
                puts.incrementAndGet();
                byte[] body = readAll(exchange.getRequestBody());
                // Immutable: first writer wins, later identical puts are no-ops.
                blobs.putIfAbsent(hash, body);
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/forge";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /** A task whose action writes a single file under the project root. */
    private static ForgeTask writingTask(String name, String relativeOutput, String content) {
        ForgeTask task = new ForgeTask(name, new TaskType("test"))
            .input(new TaskInput.ValueInput(name + ":content", content))
            .output(new TaskOutput(name + ":out", Collections.singletonList(relativeOutput)));
        task.action(ctx -> {
            File f = new File(ctx.getProjectRoot(), relativeOutput);
            try {
                Files.createDirectories(f.getParentFile().toPath());
                Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return TaskResult.failure(e.getMessage());
            }
            return TaskResult.success("wrote " + relativeOutput);
        });
        return task;
    }

    @Test
    void getReturnsEmptyOnMissAndBytesOnHit() {
        RemoteCache cache = new RemoteCache(baseUrl);
        assertFalse(cache.get("missing").isPresent(), "unknown hash is a miss");

        blobs.put("abc", "payload".getBytes(StandardCharsets.UTF_8));
        Optional<byte[]> hit = cache.get("abc");
        assertTrue(hit.isPresent());
        assertEquals("payload", new String(hit.get(), StandardCharsets.UTF_8));
    }

    @Test
    void putStoresBytesRetrievableByGet() {
        RemoteCache cache = new RemoteCache(baseUrl);
        assertTrue(cache.put("h1", "content".getBytes(StandardCharsets.UTF_8)));
        assertEquals("content", new String(blobs.get("h1"), StandardCharsets.UTF_8));
    }

    @Test
    void malformedUrlFailsFast() {
        assertThrows(ForgeException.class, () -> new RemoteCache("ftp://cache.example"));
        assertThrows(ForgeException.class, () -> new RemoteCache("not a url"));
        assertThrows(ForgeException.class, () -> new RemoteCache(""));
    }

    @Test
    void unreachableServerDegradesToMissNeverFails() {
        // Port 1 is reserved and refuses connections.
        RemoteCache cache = new RemoteCache("http://127.0.0.1:1/forge");
        assertFalse(cache.get("anything").isPresent(), "a network failure is a miss, not an error");
        assertFalse(cache.put("anything", new byte[]{1, 2, 3}), "upload failure is reported, not fatal");
    }

    @Test
    void aStoredBuildIsReusedOnAnotherMachineViaRemoteCache(@TempDir Path origin, @TempDir Path other) throws Exception {
        RemoteCache remote = new RemoteCache(baseUrl);
        TaskCache producer = new TaskCache(origin.toFile(), remote);
        ForgeTask task = writingTask("compile", "target/out.txt", "hello");
        String hash = task.computeHash(new HashMap<>());

        // Producer: miss, execute, store, upload at end of build.
        assertFalse(producer.isUpToDate(hash, task));
        assertTrue(task.getAction().execute(new io.github.yasmramos.forge.core.task.TaskContext(origin.toFile())).isSuccess());
        try {
            producer.store(hash, task, origin.toFile());
        } catch (IOException e) {
            fail(e);
        }
        assertEquals(1, producer.flushRemote(), "the freshly built entry must be uploaded");

        // Consumer: a different project root with an empty local cache hits the
        // remote, seeds locally, and restores the artifact.
        TaskCache consumer = new TaskCache(other.toFile(), remote);
        assertTrue(consumer.isUpToDate(hash, task), "remote entry must count as a hit");
        try {
            consumer.restore(hash, task, other.toFile());
        } catch (IOException e) {
            fail(e);
        }
        assertTrue(Files.isRegularFile(other.resolve("target/out.txt")));
        assertTrue(gets.get() >= 1, "the consumer must have queried the remote");
    }

    @Test
    void corruptRemoteEntryIsIgnored(@TempDir Path dir) {
        blobs.put("bad", "this is not a gzip tar archive".getBytes(StandardCharsets.UTF_8));
        TaskCache cache = new TaskCache(dir.toFile(), new RemoteCache(baseUrl));
        ForgeTask task = writingTask("t", "out.txt", "x");
        assertFalse(cache.isUpToDate("bad", task), "a non-archive entry must not be treated as a hit");
    }

    @Test
    void failedBuildUploadsNothing(@TempDir Path dir) {
        TaskCache cache = new TaskCache(dir.toFile(), new RemoteCache(baseUrl));
        assertEquals(0, cache.flushRemote(), "nothing pending -> nothing uploaded");
        assertEquals(0, puts.get());
    }

    @Test
    void transientServerErrorsAreRetried(@TempDir Path dir) throws IOException {
        // A context that fails the first two GETs with 500, then serves the blob.
        AtomicInteger calls = new AtomicInteger();
        byte[] blob = "recovered".getBytes(StandardCharsets.UTF_8);
        server.createContext("/flaky", exchange -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                exchange.sendResponseHeaders(200, blob.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(blob);
                }
            }
            exchange.close();
        });
        RemoteCache cache = new RemoteCache(baseUrl.replace("/forge", "/flaky"));
        Optional<byte[]> got = cache.get("k");
        assertTrue(got.isPresent(), "5xx must be retried, not treated as a permanent miss");
        assertEquals("recovered", new String(got.get(), StandardCharsets.UTF_8));
        assertEquals(3, calls.get(), "two failures then success");
    }

    @Test
    void cacheEntryCannotEscapeProjectRoot(@TempDir Path dir) throws Exception {
        // Craft a malicious archive: a single entry with a path-traversal name.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveWriter tar = new TarArchiveWriter(new java.util.zip.GZIPOutputStream(raw))) {
            tar.write("../escaped.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        }
        String hash = "evil";
        blobs.put(hash, raw.toByteArray());

        TaskCache cache = new TaskCache(dir.toFile(), new RemoteCache(baseUrl));
        ForgeTask task = writingTask("t", "out.txt", "x");
        assertTrue(cache.isUpToDate(hash, task), "well-formed archive is a hit");
        IOException e = assertThrows(IOException.class,
            () -> cache.restore(hash, task, dir.toFile()));
        assertTrue(e.getMessage().contains("escapes project root"), e.getMessage());
        assertFalse(Files.exists(dir.getParent().resolve("escaped.txt")), "must not write outside the root");
    }
}
