package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.util.Map;
import java.util.TreeMap;

/**
 * Generates the publishing POM for a Forge project (AGENT.md section 6).
 *
 * <p>Publishing to Maven Central is non-negotiable, so the generated POM must be
 * valid there without manual edits: it always carries the required fields
 * (modelVersion, coordinates, name, description, url, license, developer, SCM)
 * when the user supplies them, and lists every declared dependency with its
 * Maven scope. Output is deterministic (section 17): dependencies are sorted by
 * coordinate and no timestamps or absolute paths appear.</p>
 */
public final class PomGenerator {

    private PomGenerator() {
    }

    /** Builds the POM XML for the given project configuration. */
    public static String generate(ProjectConfig config) {
        ProjectConfig.PublishSettings pub = config.getPublishSettings();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0")
           .append(" http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        xml.append("  <modelVersion>4.0.0</modelVersion>\n\n");

        xml.append("  <groupId>").append(escape(config.getGroup())).append("</groupId>\n");
        xml.append("  <artifactId>").append(escape(config.getName())).append("</artifactId>\n");
        xml.append("  <version>").append(escape(config.getVersion())).append("</version>\n");
        xml.append("  <packaging>").append(escape(packaging(config))).append("</packaging>\n\n");

        appendIfPresent(xml, "  <name>", config.getName(), "</name>\n");
        appendIfPresent(xml, "  <description>", config.getDescription(), "</description>\n");
        appendIfPresent(xml, "  <url>", pub.getUrl(), "</url>\n");

        if (pub.getLicenseName() != null || pub.getLicenseUrl() != null) {
            xml.append("\n  <licenses>\n    <license>\n");
            appendIfPresent(xml, "      <name>", pub.getLicenseName(), "</name>\n");
            appendIfPresent(xml, "      <url>", pub.getLicenseUrl(), "</url>\n");
            xml.append("      <distribution>repo</distribution>\n");
            xml.append("    </license>\n  </licenses>\n");
        }

        appendDevelopers(xml, pub);
        appendScm(xml, pub);
        appendDependencies(xml, config);

        xml.append("</project>\n");
        return xml.toString();
    }

    private static void appendDevelopers(StringBuilder xml, ProjectConfig.PublishSettings pub) {
        String[] ids = split(pub.getDeveloperIds());
        String[] names = split(pub.getDeveloperNames());
        String[] emails = split(pub.getDeveloperEmails());
        int count = Math.max(ids.length, Math.max(names.length, emails.length));
        if (count == 0) {
            return;
        }
        xml.append("\n  <developers>\n");
        for (int i = 0; i < count; i++) {
            xml.append("    <developer>\n");
            appendIfPresent(xml, "      <id>", at(ids, i), "</id>\n");
            appendIfPresent(xml, "      <name>", at(names, i), "</name>\n");
            appendIfPresent(xml, "      <email>", at(emails, i), "</email>\n");
            xml.append("    </developer>\n");
        }
        xml.append("  </developers>\n");
    }

    private static void appendScm(StringBuilder xml, ProjectConfig.PublishSettings pub) {
        if (pub.getScmUrl() == null || pub.getScmUrl().trim().isEmpty()) {
            return;
        }
        String url = pub.getScmUrl().trim();
        // Strip the scm:git: prefix for <url>, keep the full form for <connection>.
        String httpUrl = url.replaceFirst("^scm:(git|svn|hg):", "").replaceFirst("^git:", "");
        xml.append("\n  <scm>\n");
        xml.append("    <connection>").append(escape(url)).append("</connection>\n");
        xml.append("    <developerConnection>").append(escape(url))
           .append("</developerConnection>\n");
        xml.append("    <url>").append(escape(httpUrl)).append("</url>\n");
        xml.append("  </scm>\n");
    }

    private static void appendDependencies(StringBuilder xml, ProjectConfig config) {
        Map<String, Object> declared = config.getDependencies();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        // Sorted by coordinate so the POM is byte-stable across runs (section 17).
        Map<String, Object> sorted = new TreeMap<>(declared);
        StringBuilder deps = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            String[] ga = splitCoordinate(entry.getKey());
            String version = DependencyScopes.versionOf(entry.getValue());
            if (ga == null || version == null || version.trim().isEmpty()) {
                continue;
            }
            String scope = DependencyScopes.scopeOf(entry.getValue());
            deps.append("    <dependency>\n");
            deps.append("      <groupId>").append(escape(ga[0])).append("</groupId>\n");
            deps.append("      <artifactId>").append(escape(ga[1])).append("</artifactId>\n");
            deps.append("      <version>").append(escape(version)).append("</version>\n");
            if (scope != null && !"compile".equals(scope)) {
                deps.append("      <scope>").append(escape(scope)).append("</scope>\n");
            }
            deps.append("    </dependency>\n");
        }
        if (deps.length() > 0) {
            xml.append("\n  <dependencies>\n").append(deps).append("  </dependencies>\n");
        }
    }

    private static String packaging(ProjectConfig config) {
        String p = config.getPackaging();
        return (p == null || p.trim().isEmpty()) ? "jar" : p.trim();
    }

    private static void appendIfPresent(StringBuilder xml, String open, String value, String close) {
        if (value != null && !value.trim().isEmpty()) {
            xml.append(open).append(escape(value.trim())).append(close);
        }
    }

    private static String[] split(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String at(String[] values, int i) {
        return i < values.length ? values[i] : null;
    }

    private static String[] splitCoordinate(String coordinate) {
        int i = coordinate.indexOf(':');
        if (i <= 0 || i == coordinate.length() - 1) {
            return null;
        }
        return new String[] {coordinate.substring(0, i), coordinate.substring(i + 1)};
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
