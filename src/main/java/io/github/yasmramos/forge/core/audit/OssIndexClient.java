package io.github.yasmramos.forge.core.audit;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the Sonatype OSS Index component-report API (AGENT.md section 18).
 *
 * <p>{@code forge audit} queries OSS Index with the resolved graph's
 * coordinates and reports known vulnerabilities. The service is optional and
 * external: it is never on the build path, and a network failure degrades to a
 * clear, actionable error ({@code FORGE-AUD-0002}) instead of a stack trace or
 * a silently empty report.</p>
 *
 * <h2>Protocol</h2>
 * <p>{@code POST <base>/api/v3/component-report} with a JSON body
 * {@code {"coordinates":["pkg:maven/group/artifact@version", ...]}} returns one
 * component object per coordinate, each carrying its {@code vulnerabilities}
 * array. The API accepts at most {@value #MAX_COORDINATES_PER_REQUEST}
 * coordinates per call, so requests are split into batches. Requests retry with
 * exponential backoff on transient failures (section 12).</p>
 */
public final class OssIndexClient {

    private static final Logger logger = LoggerFactory.getLogger(OssIndexClient.class);

    /** Public OSS Index endpoint; overridable so tests can point at a local server. */
    public static final String DEFAULT_BASE_URL = "https://ossindex.sonatype.org";

    static final int MAX_COORDINATES_PER_REQUEST = 128;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 200;

    private final String baseUrl;
    private final String username;
    private final String token;

    public OssIndexClient(String baseUrl, String username, String token) {
        String normalized = baseUrl == null || baseUrl.trim().isEmpty()
            ? DEFAULT_BASE_URL : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URL url = new URL(normalized);
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new ForgeException(ErrorCode.AUD_0003,
                    "OSS Index URL '" + baseUrl + "' must use http or https");
            }
        } catch (MalformedURLException e) {
            throw new ForgeException(ErrorCode.AUD_0003,
                "OSS Index URL '" + baseUrl + "' is not a valid URL");
        }
        this.baseUrl = normalized;
        this.username = username == null || username.trim().isEmpty() ? null : username.trim();
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
    }

    /** One component's report: its PURL and the vulnerabilities found for it. */
    public static final class ComponentReport {
        private final String purl;
        private final List<Vulnerability> vulnerabilities;

        public ComponentReport(String purl, List<Vulnerability> vulnerabilities) {
            this.purl = purl;
            this.vulnerabilities = Collections.unmodifiableList(vulnerabilities);
        }

        public String getPurl() { return purl; }

        public List<Vulnerability> getVulnerabilities() { return vulnerabilities; }
    }

    /** A single known vulnerability affecting a component. */
    public static final class Vulnerability {
        private final String id;
        private final String title;
        private final double cvssScore;
        private final String cvssVector;
        private final String cve;
        private final String reference;

        public Vulnerability(String id, String title, double cvssScore, String cvssVector,
                      String cve, String reference) {
            this.id = id;
            this.title = title;
            this.cvssScore = cvssScore;
            this.cvssVector = cvssVector;
            this.cve = cve;
            this.reference = reference;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public double getCvssScore() { return cvssScore; }
        public String getCvssVector() { return cvssVector; }
        public String getCve() { return cve; }
        public String getReference() { return reference; }

        /**
         * Severity band derived from the CVSS score, using the CVSS v3 ranges
         * (none / low / medium / high / critical). Kept here so the CLI and any
         * future report format agree on what "high" means.
         */
        public String severity() {
            if (cvssScore >= 9.0) {
                return "critical";
            }
            if (cvssScore >= 7.0) {
                return "high";
            }
            if (cvssScore >= 4.0) {
                return "medium";
            }
            if (cvssScore > 0.0) {
                return "low";
            }
            return "unknown";
        }
    }

    /**
     * Queries OSS Index for the given coordinates (each {@code group:artifact:version})
     * and returns one report per coordinate, in the same order. Batches larger
     * inputs transparently.
     *
     * @throws ForgeException {@code FORGE-AUD-0002} when the service cannot be
     *                        reached after the configured retries
     */
    public List<ComponentReport> report(List<String> coordinates) {
        List<ComponentReport> reports = new ArrayList<>();
        for (int start = 0; start < coordinates.size(); start += MAX_COORDINATES_PER_REQUEST) {
            int end = Math.min(start + MAX_COORDINATES_PER_REQUEST, coordinates.size());
            reports.addAll(reportBatch(coordinates.subList(start, end)));
        }
        return reports;
    }

    private List<ComponentReport> reportBatch(List<String> coordinates) {
        List<String> purls = new ArrayList<>(coordinates.size());
        for (String coordinate : coordinates) {
            purls.add(toPurl(coordinate));
        }
        String body = buildRequestBody(purls);
        String response = post("/api/v3/component-report", body);
        return parseReports(response);
    }

    /** Converts {@code group:artifact:version} into a Maven PURL. */
    static String toPurl(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                "coordinate must be group:artifact:version but was '" + coordinate + "'");
        }
        return "pkg:maven/" + parts[0] + "/" + parts[1] + "@" + parts[2];
    }

    private static String buildRequestBody(List<String> purls) {
        StringBuilder sb = new StringBuilder("{\"coordinates\":[");
        for (int i = 0; i < purls.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(purls.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }

    @SuppressWarnings("unchecked")
    private static List<ComponentReport> parseReports(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof List)) {
            throw new ForgeException(ErrorCode.AUD_0004,
                "OSS Index returned an unexpected document (expected a JSON array)");
        }
        List<ComponentReport> reports = new ArrayList<>();
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> component = (Map<String, Object>) item;
            String purl = asString(component.get("coordinates"));
            List<Vulnerability> vulnerabilities = new ArrayList<>();
            Object vulns = component.get("vulnerabilities");
            if (vulns instanceof List) {
                for (Object v : (List<Object>) vulns) {
                    if (v instanceof Map) {
                        vulnerabilities.add(toVulnerability((Map<String, Object>) v));
                    }
                }
            }
            reports.add(new ComponentReport(purl, vulnerabilities));
        }
        return reports;
    }

    private static Vulnerability toVulnerability(Map<String, Object> v) {
        String id = asString(v.get("id"));
        String title = asString(v.get("title"));
        if (title == null) {
            title = asString(v.get("displayName"));
        }
        String cve = asString(v.get("cve"));
        if (cve == null) {
            cve = asString(v.get("displayName"));
        }
        String reference = asString(v.get("reference"));
        double score = asDouble(v.get("cvssScore"));
        String vector = asString(v.get("cvssVector"));
        return new Vulnerability(id, title, score, vector, cve, reference);
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private String post(String path, String body) {
        String url = baseUrl + path;
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
                if (username != null && token != null) {
                    String auth = Base64.getEncoder().encodeToString(
                        (username + ":" + token).getBytes(StandardCharsets.UTF_8));
                    conn.setRequestProperty("Authorization", "Basic " + auth);
                }
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream()) {
                        return new String(readAll(in), StandardCharsets.UTF_8);
                    }
                }
                if (status == 429 || status >= 500) {
                    // Rate-limited or transient: retry with backoff (section 12).
                    if (attempt < ATTEMPTS) {
                        logger.warn("OSS Index POST {} returned HTTP {}; retrying", url, status);
                        backoff(attempt);
                        continue;
                    }
                    throw new ForgeException(ErrorCode.NET_0001,
                        "OSS Index at " + baseUrl + " returned HTTP " + status
                            + " after " + ATTEMPTS + " attempts; try again later or run "
                            + "'forge audit' with --offline to skip the query");
                }
                throw new ForgeException(ErrorCode.NET_0001,
                    "OSS Index at " + baseUrl + " returned HTTP " + status);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < ATTEMPTS) {
                    backoff(attempt);
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new ForgeException(ErrorCode.NET_0001,
            "could not reach OSS Index at " + baseUrl + " after " + ATTEMPTS
                + " attempts: " + (lastFailure == null ? "unknown error" : lastFailure.getMessage()),
            lastFailure);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_BASE_MS << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
