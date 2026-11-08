package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates Gradle Module Metadata for a Forge project (AGENT.md section 6).
 *
 * <p>Modern Kotlin/JVM libraries publish Gradle Module Metadata (GMM), and
 * consuming them correctly requires it. Producing it as well means a library
 * built with Forge is consumable by Gradle without degradation, including
 * variant-aware dependency alignment. The document is deterministic: variants,
 * dependencies and files are emitted in sorted order (section 17).</p>
 */
public final class GradleModuleMetadataGenerator {

    /** GMM format version emitted by this generator. */
    public static final String FORMAT_VERSION = "1.1";

    private GradleModuleMetadataGenerator() {
    }

    /** Builds the {@code .module} JSON for the given project configuration. */
    public static String generate(ProjectConfig config) {
        String group = config.getGroup() == null ? "" : config.getGroup();
        String name = config.getName() == null ? "" : config.getName();
        String version = config.getVersion() == null ? "" : config.getVersion();
        String artifactId = name + "-" + version;

        Map<String, String> compileDeps = new TreeMap<>();
        Map<String, String> runtimeDeps = new TreeMap<>();
        splitDependencies(config, compileDeps, runtimeDeps);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"formatVersion\": ").append(jsonString(FORMAT_VERSION)).append(",\n");
        sb.append("  \"component\": {\n");
        sb.append("    \"group\": ").append(jsonString(group)).append(",\n");
        sb.append("    \"module\": ").append(jsonString(name)).append(",\n");
        sb.append("    \"version\": ").append(jsonString(version)).append(",\n");
        sb.append("    \"attributes\": {\n");
        sb.append("      \"org.gradle.status\": ").append(jsonString(status(version))).append("\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"createdBy\": {\n");
        sb.append("    \"gradle\": { \"version\": ").append(jsonString("forge"))
          .append(", \"buildId\": ").append(jsonString(artifactId)).append(" }\n");
        sb.append("  },\n");

        sb.append("  \"variants\": [\n");
        appendVariant(sb, "apiElements", "library", "api", compileDeps, artifactId, false);
        sb.append(",\n");
        appendVariant(sb, "runtimeElements", "library", "runtime", runtimeDeps, artifactId, true);
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendVariant(StringBuilder sb, String variantName, String usage,
                                      String category, Map<String, String> dependencies,
                                      String artifactId, boolean runtime) {
        sb.append("    {\n");
        sb.append("      \"name\": ").append(jsonString(variantName)).append(",\n");
        sb.append("      \"attributes\": {\n");
        sb.append("        \"org.gradle.category\": ").append(jsonString(category)).append(",\n");
        sb.append("        \"org.gradle.usage\": ").append(jsonString(usage)).append("\n");
        sb.append("      },\n");
        sb.append("      \"dependencies\": [\n");
        int i = 0;
        for (Map.Entry<String, String> dep : dependencies.entrySet()) {
            String[] ga = splitCoordinate(dep.getKey());
            if (ga == null) {
                continue;
            }
            sb.append("        { \"group\": ").append(jsonString(ga[0]))
              .append(", \"module\": ").append(jsonString(ga[1]))
              .append(", \"version\": { \"requires\": ").append(jsonString(dep.getValue()))
              .append(" } }").append(++i < dependencies.size() ? ",\n" : "\n");
        }
        sb.append("      ],\n");
        sb.append("      \"files\": [\n");
        sb.append("        { \"name\": ").append(jsonString(artifactId + ".jar"))
          .append(", \"url\": ").append(jsonString(artifactId + ".jar")).append(" },\n");
        sb.append("        { \"name\": ").append(jsonString(artifactId + ".module"))
          .append(", \"url\": ").append(jsonString(artifactId + ".module")).append(" }\n");
        sb.append("      ]\n");
        sb.append("    }");
    }

    /**
     * Splits declared dependencies into the api (compile) and runtime variant
     * sets. Test and provided scopes are intentionally excluded: they are not
     * part of what a consumer resolves.
     */
    private static void splitDependencies(ProjectConfig config,
                                          Map<String, String> compileDeps,
                                          Map<String, String> runtimeDeps) {
        Map<String, Object> declared = config.getDependencies();
        if (declared == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            String scope = DependencyScopes.scopeOf(entry.getValue());
            String version = DependencyScopes.versionOf(entry.getValue());
            if (version == null || version.trim().isEmpty()) {
                continue;
            }
            if (DependencyScopes.COMPILE.contains(scope)) {
                compileDeps.put(entry.getKey(), version);
                runtimeDeps.put(entry.getKey(), version);
            } else if ("runtime".equals(scope)) {
                runtimeDeps.put(entry.getKey(), version);
            }
        }
    }

    private static String status(String version) {
        return version != null && version.endsWith("-SNAPSHOT") ? "integration" : "release";
    }

    private static String[] splitCoordinate(String coordinate) {
        int i = coordinate.indexOf(':');
        if (i <= 0 || i == coordinate.length() - 1) {
            return null;
        }
        return new String[] {coordinate.substring(0, i), coordinate.substring(i + 1)};
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
