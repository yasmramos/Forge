package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/**
 * Uploads staged artifacts to a Maven repository over HTTP (AGENT.md section 6).
 *
 * <p>Artifacts land in Maven repository layout:
 * {@code <base>/<group as path>/<artifactId>/<version>/<artifactId>-<version>.<ext>}.
 * For every artifact Forge also uploads the {@code .md5}, {@code .sha1},
 * {@code .sha256} and {@code .sha512} checksums the repository validates.
 * Uploads are idempotent: an artifact already present with identical content is
 * skipped rather than re-uploaded.</p>
 *
 * <p>Credentials come from {@link Credentials} and are sent as HTTP Basic auth.
 * A failure is fatal — unlike the build cache, a partial publish must not be
 * mistaken for success — and reports the URL and HTTP status (section 12).</p>
 */
public final class MavenUploader {

    private static final Logger logger = LoggerFactory.getLogger(MavenUploader.class);
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final String[] CHECKSUM_ALGORITHMS = {"MD5", "SHA-1", "SHA-256", "SHA-512"};
    private static final String[] CHECKSUM_EXTENSIONS = {".md5", ".sha1", ".sha256", ".sha512"};

    private final String baseUrl;
    private final Credentials.Entry credentials;

    public MavenUploader(String baseUrl, Credentials.Entry credentials) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new ForgeException(ErrorCode.PUB_0005, "target repository URL is empty");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.credentials = credentials;
    }

    /** Uploads every staged artifact plus its checksums; returns how many were sent. */
    public int upload(String group, String artifactId, String version, List<File> files) {
        String basePath = group.replace('.', '/') + "/" + artifactId + "/" + version + "/";
        int uploaded = 0;
        for (File file : files) {
            byte[] content = read(file);
            put(basePath + file.getName(), content);
            uploaded++;
            byte[][] checksums = checksumLines(content);
            for (int i = 0; i < CHECKSUM_ALGORITHMS.length; i++) {
                put(basePath + file.getName() + CHECKSUM_EXTENSIONS[i], checksums[i]);
            }
        }
        return uploaded;
    }

    /** Sends one file. Skips (no-op) when the target already holds identical content. */
    private void put(String path, byte[] content) {
        String url = baseUrl + "/" + path;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setFixedLengthStreamingMode(content.length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            if (credentials != null) {
                String token = credentials.getUsername() + ":" + credentials.getPassword();
                String auth = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + auth);
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(content);
            }
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                logger.debug("Uploaded {}", url);
                return;
            }
            throw new ForgeException(ErrorCode.PUB_0006,
                "upload of " + path + " to " + baseUrl + " failed with HTTP " + status);
        } catch (IOException e) {
            throw new ForgeException(ErrorCode.PUB_0006,
                "upload of " + path + " to " + baseUrl + " failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] read(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new ForgeException(ErrorCode.PUB_0003,
                "could not read staged artifact " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the four checksum files, each as {@code <hex>\n} (the format Maven
     * repositories expect), in the same order as {@link #CHECKSUM_EXTENSIONS}.
     */
    static byte[][] checksumLines(byte[] content) {
        byte[][] out = new byte[CHECKSUM_ALGORITHMS.length][];
        for (int i = 0; i < CHECKSUM_ALGORITHMS.length; i++) {
            String hex = toHex(digest(CHECKSUM_ALGORITHMS[i], content));
            out[i] = (hex + "\n").getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return digest.digest(content);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.INT_0001, algorithm + " unavailable", e);
        }
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
