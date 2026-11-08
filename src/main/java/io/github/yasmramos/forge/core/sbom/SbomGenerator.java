package io.github.yasmramos.forge.core.sbom;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Map;

/**
 * Generates a CycloneDX SBOM (AGENT.md section 18).
 *
 * <p>Provenance matters the moment artifacts leave the machine, so the SBOM is
 * produced from the resolved dependency graph and its lockfile checksums. The
 * output is deterministic: components are sorted by {@code group:artifact},
 * their hashes come from the locked content, and the serial number is derived
 * from the document body rather than a random UUID, so the same graph always
 * yields the same SBOM (section 17).</p>
 */
public final class SbomGenerator {

    /** One component: a resolved dependency with its identity and checksum. */
    public static final class Component {
        private final String group;
        private final String name;
        private final String version;
        private final String scope;      // compile | runtime | provided | test
        private final String sha256;     // hex, or null when unknown
        private final String purl;

        public Component(String group, String name, String version, String scope, String sha256) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.scope = scope;
            this.sha256 = sha256;
            this.purl = "pkg:maven/" + group + "/" + name + "@" + version;
        }

        public String getGroup() { return group; }
        public String getName() { return name; }
        public String getVersion() { return version; }
        public String getScope() { return scope; }
        public String getSha256() { return sha256; }
        public String getPurl() { return purl; }

        /** Stable identity used for sorting and de-duplication. */
        String key() {
            return group + ":" + name;
        }
    }

    private SbomGenerator() {
    }

    /**
     * Builds the CycloneDX document for a project.
     *
     * @param projectName    name from [project]
     * @param projectVersion version from [project]
     * @param components     resolved dependencies (order irrelevant; sorted here)
     * @param timestamp      fixed timestamp (reproducibility); never "now"
     * @return the document as pretty JSON
     */
    public static String generate(String projectName, String projectVersion,
                                  List<Component> components, Instant timestamp) {
        Map<String, Component> byKey = new TreeMap<>();
        for (Component c : components) {
            // Same GA can appear once per resolved version; the lock pins one,
            // so keep the last (deterministic because input order is stable).
            byKey.put(c.key(), c);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"bomFormat\": \"CycloneDX\",\n");
        sb.append("  \"specVersion\": \"1.5\",\n");
        sb.append("  \"version\": 1,\n");
        String serial = serialNumber(projectName, projectVersion, byKey.values(), timestamp);
        sb.append("  \"serialNumber\": \"").append(serial).append("\",\n");
        sb.append("  \"metadata\": {\n");
        sb.append("    \"timestamp\": \"").append(timestamp).append("\",\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"application\",\n");
        sb.append("      \"group\": \"\",\n");
        sb.append("      \"name\": ").append(jsonString(projectName)).append(",\n");
        sb.append("      \"version\": ").append(jsonString(projectVersion)).append("\n");
        sb.append("    },\n");
        sb.append("    \"tools\": [ { \"vendor\": \"Forge\", \"name\": \"forge\", \"version\": \"")
          .append("1.0.0").append("\" } ]\n");
        sb.append("  },\n");
        sb.append("  \"components\": [\n");
        int i = 0;
        for (Component c : byKey.values()) {
            sb.append("    {\n");
            sb.append("      \"type\": \"library\",\n");
            sb.append("      \"group\": ").append(jsonString(c.getGroup())).append(",\n");
            sb.append("      \"name\": ").append(jsonString(c.getName())).append(",\n");
            sb.append("      \"version\": ").append(jsonString(c.getVersion())).append(",\n");
            sb.append("      \"scope\": ").append(jsonString(c.getScope())).append(",\n");
            sb.append("      \"purl\": ").append(jsonString(c.getPurl()));
            if (c.getSha256() != null && !c.getSha256().isEmpty()) {
                sb.append(",\n");
                sb.append("      \"hashes\": [ { \"alg\": \"SHA-256\", \"content\": ")
                  .append(jsonString(c.getSha256())).append(" } ]\n");
            } else {
                sb.append('\n');
            }
            sb.append("    }").append(++i < byKey.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Deterministic serial number: derived from the content, not random. */
    private static String serialNumber(String name, String version,
                                       Iterable<Component> components, Instant timestamp) {
        StringBuilder seed = new StringBuilder(name).append('@').append(version)
            .append('#').append(timestamp);
        for (Component c : components) {
            seed.append('|').append(c.key()).append(':').append(c.getVersion())
                .append(':').append(c.getSha256());
        }
        return "urn:uuid:" + uuidFromSha256(sha256Hex(seed.toString()));
    }

    /** Formats the first 16 bytes of a hash as a name-based UUID (version 5). */
    private static String uuidFromSha256(String hex) {
        String h = hex;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32 && i < h.length(); i++) {
            sb.append(h.charAt(i));
            if (sb.length() == 8 || sb.length() == 13 || sb.length() == 18 || sb.length() == 23) {
                sb.append('-');
            }
        }
        // Force the version (5) and variant (8..b) nibbles so it is a valid UUID.
        sb.setCharAt(14, '5');
        char variant = sb.charAt(19);
        sb.setCharAt(19, "89ab".indexOf(Character.toLowerCase(variant)) >= 0 ? variant : '8');
        return sb.toString();
    }

    /** SHA-256 of a file's content, or null when the file is missing. */
    public static String sha256Of(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.INT_0001,
                "could not hash " + file.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ForgeException(ErrorCode.INT_0001, "SHA-256 unavailable", e);
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

    private static String jsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
