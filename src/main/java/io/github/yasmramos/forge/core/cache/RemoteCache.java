package io.github.yasmramos.forge.core.cache;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

/**
 * Client for the Forge remote build cache (AGENT.md section 3). The wire
 * protocol is deliberately small so anyone can run a server on their own
 * infrastructure; Forge ships only the client.
 *
 * <h2>Protocol</h2>
 * <p>The cache is addressed by the task's content hash — never by time, name or
 * project, because invalidation is content-based (section 3). For a base URL
 * {@code https://cache.example/forge}, the entry for hash {@code H} lives at
 * {@code https://cache.example/forge/H} and holds exactly the bytes of the
 * local entry ({@code outputs.tar.gz}, gzip-only, no mtime):</p>
 * <ul>
 *   <li>{@code GET /forge/H} — {@code 200} with the archive bytes on a hit,
 *       {@code 404} on a miss. Any other status is treated as a miss.</li>
 *   <li>{@code PUT /forge/H} with the archive bytes — {@code 2xx} stores the
 *       entry. Entries are immutable: a {@code PUT} for an existing hash is a
 *       no-op, so concurrent uploads of identical content are always safe.</li>
 * </ul>
 *
 * <p>Because the hash already covers every input, toolchain, Forge and plugin
 * version (section 3), the server needs no knowledge of projects: it is a
 * content-addressed blob store. Downloads retry with exponential backoff and a
 * failure is never fatal — the build falls back to local execution rather than
 * failing (section 12).</p>
 */
public final class RemoteCache {

    private static final Logger logger = LoggerFactory.getLogger(RemoteCache.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 200;

    private final String baseUrl;

    /** Builds a client for {@code baseUrl}, failing fast on a malformed URL. */
    public RemoteCache(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new ForgeException(ErrorCode.CCH_0002, "remote cache URL is empty");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URL url = new URL(normalized);
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new ForgeException(ErrorCode.CCH_0002,
                    "remote cache URL '" + baseUrl + "' must use http or https");
            }
        } catch (MalformedURLException e) {
            throw new ForgeException(ErrorCode.CCH_0002,
                "remote cache URL '" + baseUrl + "' is not a valid URL");
        }
        this.baseUrl = normalized;
    }

    /** The normalized base URL this client talks to. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Downloads the entry for {@code hash}. Returns empty on a miss ({@code 404})
     * or when the server cannot be reached; a network failure degrades to local
     * execution and never fails the build.
     */
    public Optional<byte[]> get(String hash) {
        String url = baseUrl + "/" + hash;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream()) {
                        return Optional.of(readAll(in));
                    }
                }
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    return Optional.empty();
                }
                if (status >= 500 && attempt < ATTEMPTS) {
                    // Transient server failure: retry with backoff (section 12).
                    logger.warn("Remote cache GET {} returned HTTP {}; retrying", url, status);
                    backoff(attempt);
                    continue;
                }
                logger.warn("Remote cache GET {} returned HTTP {}; treating as a miss",
                    url, status);
                return Optional.empty();
            } catch (IOException e) {
                if (attempt == ATTEMPTS) {
                    logger.warn("Remote cache GET {} failed after {} attempts ({}); "
                        + "building locally", url, ATTEMPTS, e.getMessage());
                    return Optional.empty();
                }
                backoff(attempt);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Uploads the entry for {@code hash}. Returns {@code true} when the server
     * stored it. Failures are logged and reported, never fatal: the local cache
     * remains authoritative and the build's result is unaffected (section 16bis).
     */
    public boolean put(String hash, byte[] archive) {
        String url = baseUrl + "/" + hash;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setFixedLengthStreamingMode(archive.length);
                conn.setRequestProperty("Content-Type", "application/gzip");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(archive);
                }
                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    return true;
                }
                if (status >= 500 && attempt < ATTEMPTS) {
                    // Transient server failure: retry with backoff (section 12).
                    logger.warn("Remote cache PUT {} returned HTTP {}; retrying", url, status);
                    backoff(attempt);
                    continue;
                }
                logger.warn("Remote cache PUT {} returned HTTP {}; entry not uploaded", url, status);
                return false;
            } catch (IOException e) {
                if (attempt == ATTEMPTS) {
                    logger.warn("Remote cache PUT {} failed after {} attempts ({}); "
                        + "entry stays local", url, ATTEMPTS, e.getMessage());
                    return false;
                }
                backoff(attempt);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MS << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** True when the bytes are a readable gzip/tar archive (guards corruption). */
    static boolean isValidArchive(byte[] archive) {
        if (archive == null || archive.length == 0) {
            return false;
        }
        try (TarArchiveReader reader = new TarArchiveReader(
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(archive)))) {
            while (reader.nextEntry() != null) {
                // Reading the whole stream proves it is a well-formed archive.
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String describe(byte[] archive) {
        return archive == null ? "0 bytes" : archive.length + " bytes";
    }
}
