package io.github.yasmramos.forge.core.audit;

import com.sun.net.httpserver.HttpServer;
import io.github.yasmramos.forge.cli.error.ForgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the OSS Index client against a real HTTP server (AGENT.md section 18).
 * No mocks: a JDK {@code HttpServer} speaks the component-report protocol, so
 * the request body, PURL mapping, batching, parsing and network-failure
 * degradation are exercised end to end.
 */
class OssIndexClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger requestCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "[]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/component-report", exchange -> {
            requestCount.incrementAndGet();
            lastRequestBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mapsCoordinatesToPurlsAndParsesFindings() {
        responseBody = "[{\"coordinates\":\"pkg:maven/com.example/lib@1.0.0\","
            + "\"vulnerabilities\":[{\"id\":\"CVE-2021-1\",\"title\":\"Bad thing\","
            + "\"cvssScore\":9.8,\"cve\":\"CVE-2021-1\","
            + "\"reference\":\"https://example.test/CVE-2021-1\"}]}]";
        OssIndexClient client = new OssIndexClient(baseUrl, null, null);

        List<OssIndexClient.ComponentReport> reports =
            client.report(Arrays.asList("com.example:lib:1.0.0"));

        assertEquals(1, reports.size());
        assertEquals("pkg:maven/com.example/lib@1.0.0", reports.get(0).getPurl());
        assertEquals(1, reports.get(0).getVulnerabilities().size());
        OssIndexClient.Vulnerability v = reports.get(0).getVulnerabilities().get(0);
        assertEquals("CVE-2021-1", v.getId());
        assertEquals(9.8, v.getCvssScore());
        assertEquals("critical", v.severity());
        assertTrue(lastRequestBody.get().contains("pkg:maven/com.example/lib@1.0.0"));
    }

    @Test
    void batchesLargeCoordinateLists() {
        OssIndexClient client = new OssIndexClient(baseUrl, null, null);
        String[] coordinates = new String[OssIndexClient.MAX_COORDINATES_PER_REQUEST + 5];
        for (int i = 0; i < coordinates.length; i++) {
            coordinates[i] = "com.example:lib" + i + ":1.0.0";
        }
        responseBody = "[]";
        // Two batches: the client must not send more than the API limit at once.
        assertEquals(0, client.report(Arrays.asList(coordinates)).size());
        assertEquals(2, requestCount.get());
    }

    @Test
    void reportsCleanComponents() {
        responseBody = "[{\"coordinates\":\"pkg:maven/com.example/lib@1.0.0\",\"vulnerabilities\":[]}]";
        OssIndexClient client = new OssIndexClient(baseUrl, null, null);
        List<OssIndexClient.ComponentReport> reports =
            client.report(Arrays.asList("com.example:lib:1.0.0"));
        assertTrue(reports.get(0).getVulnerabilities().isEmpty());
    }

    @Test
    void degradesToNetworkErrorWhenUnreachable() {
        // Port 1 is reserved and never listening: connection refused, no retries left.
        OssIndexClient client = new OssIndexClient("http://127.0.0.1:1", null, null);
        ForgeException ex = assertThrows(ForgeException.class,
            () -> client.report(Arrays.asList("com.example:lib:1.0.0")));
        assertEquals("FORGE-NET-0001", ex.getCode());
    }

    @Test
    void rejectsInvalidCoordinate() {
        OssIndexClient client = new OssIndexClient(baseUrl, null, null);
        assertThrows(IllegalArgumentException.class,
            () -> client.report(Arrays.asList("no-version")));
    }

    @Test
    void rejectsNonHttpUrl() {
        assertThrows(ForgeException.class,
            () -> new OssIndexClient("ftp://example.test", null, null));
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
