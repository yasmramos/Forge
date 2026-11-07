package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.util.Map;

/**
 * Generates the minimal Maven POM for a module artifact installed in the
 * multi-module workspace (AGENT.md section 5).
 *
 * <p>The POM is what makes inter-module dependencies transitive: when module
 * {@code app} depends on {@code core} by coordinate, Aether reads {@code core}'s
 * POM to discover that {@code core} itself depends on, say, {@code logback}, and
 * pulls it in. Only compile-scope dependencies are listed, matching what a
 * consumer needs on its own compile classpath.</p>
 *
 * <p>This is deliberately not the full publishing POM of AGENT.md section 6
 * (licenses, developers, SCM); it exists solely so the workspace is resolvable.</p>
 */
final class ModulePom {

    private ModulePom() {
    }

    static String generate(ProjectConfig config) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        xml.append("  <modelVersion>4.0.0</modelVersion>\n");
        xml.append("  <groupId>").append(escape(config.getGroup())).append("</groupId>\n");
        xml.append("  <artifactId>").append(escape(config.getName())).append("</artifactId>\n");
        xml.append("  <version>").append(escape(config.getVersion())).append("</version>\n");
        xml.append("  <packaging>jar</packaging>\n");

        StringBuilder deps = new StringBuilder();
        for (Map.Entry<String, Object> entry : config.getDependencies().entrySet()) {
            if (!DependencyScopes.COMPILE.contains(DependencyScopes.scopeOf(entry.getValue()))) {
                continue;
            }
            String version = DependencyScopes.versionOf(entry.getValue());
            if (version == null || version.trim().isEmpty()) {
                continue;
            }
            String[] ga = splitGa(entry.getKey());
            if (ga == null) {
                continue;
            }
            deps.append("    <dependency>\n");
            deps.append("      <groupId>").append(escape(ga[0])).append("</groupId>\n");
            deps.append("      <artifactId>").append(escape(ga[1])).append("</artifactId>\n");
            deps.append("      <version>").append(escape(version)).append("</version>\n");
            deps.append("      <scope>compile</scope>\n");
            deps.append("    </dependency>\n");
        }

        if (deps.length() > 0) {
            xml.append("  <dependencies>\n").append(deps).append("  </dependencies>\n");
        }
        xml.append("</project>\n");
        return xml.toString();
    }

    private static String[] splitGa(String coordinate) {
        int i = coordinate.indexOf(':');
        if (i <= 0 || i == coordinate.length() - 1) {
            return null;
        }
        return new String[] {coordinate.substring(0, i), coordinate.substring(i + 1)};
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
