package io.github.yasmramos.forge.core.importer;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a Maven {@code pom.xml} into a {@link BuildModel} (AGENT.md section 14).
 *
 * <p>It covers the parts that decide how a project builds - coordinates,
 * properties, dependencies and their scopes, source roots, the Java level and
 * executable main class - and records everything it cannot translate as a TODO.
 * "Reasonable, not perfect" is the contract: the generated file must build the
 * common case and be honest about the rest.</p>
 */
public final class MavenPomImporter {

    /** Maven scope -> Forge dependency scope. */
    private static final Map<String, String> SCOPE_MAP = new LinkedHashMap<>();
    static {
        SCOPE_MAP.put("compile", "compile");
        SCOPE_MAP.put("runtime", "runtime");
        SCOPE_MAP.put("test", "test");
        SCOPE_MAP.put("provided", "provided");
    }

    private MavenPomImporter() {
    }

    /**
     * Parses a {@code pom.xml}.
     *
     * @param pom the pom.xml file
     * @return the neutral build model
     * @throws ForgeException {@code FORGE-IMP-0002} when the XML is unreadable
     */
    public static BuildModel importPom(File pom) {
        Document doc = parse(pom);
        Element project = doc.getDocumentElement();
        BuildModel model = new BuildModel();

        String parentVersion = null;
        Element parent = firstChild(project, "parent");
        if (parent != null) {
            parentVersion = text(parent, "version");
        }

        String artifactId = text(project, "artifactId");
        model.setName(artifactId != null ? artifactId : "imported");
        model.setGroup(firstNonBlank(text(project, "groupId"), parentGroup(project)));
        model.setVersion(firstNonBlank(text(project, "version"), parentVersion, "1.0.0"));
        model.setPackaging(firstNonBlank(text(project, "packaging"), "jar"));
        model.setDescription(text(project, "description"));

        // [project.properties]: Maven properties become reusable ${...} values.
        Element properties = firstChild(project, "properties");
        if (properties != null) {
            for (Element p : children(properties)) {
                model.getProperties().put(p.getTagName(), p.getTextContent().trim());
            }
        }

        String javaVersion = firstNonBlank(
            model.getProperties().get("maven.compiler.release"),
            model.getProperties().get("maven.compiler.source"),
            model.getProperties().get("java.version"));
        if (javaVersion != null) {
            model.setLanguage(javaVersion);
        }

        // [repositories]: Maven <repositories> entries.
        Element repositories = firstChild(project, "repositories");
        if (repositories != null) {
            for (Element repo : children(repositories, "repository")) {
                String id = text(repo, "id");
                String url = text(repo, "url");
                if (id != null && url != null) {
                    model.getRepositories().put(id, url);
                }
            }
        }

        importDependencies(project, model);

        // [source.main]/[source.test]: default Maven layout, overridden only when
        // build/sourceDirectory declares otherwise.
        String sourceDir = null;
        Element build = firstChild(project, "build");
        if (build != null) {
            sourceDir = text(build, "sourceDirectory");
            Element plugins = firstChild(build, "plugins");
            if (plugins != null && !children(plugins).isEmpty()) {
                model.addTodo("Maven build plugins are not translated automatically; "
                    + "re-declare their behavior in forge.toml or a Forge plugin.");
            }
        }
        model.getMainJavaSources().add(firstNonBlank(sourceDir, "src/main/java"));
        model.getTestJavaSources().add("src/test/java");

        detectMainClass(project, model);
        return model;
    }

    private static void importDependencies(Element project, BuildModel model) {
        Element dependencies = firstChild(project, "dependencies");
        if (dependencies != null) {
            for (Element dep : children(dependencies, "dependency")) {
                addDependency(model, dep);
            }
        }

        // [bom]: dependencyManagement with scope=import is a BOM import
        // (AGENT.md section 9). Other managed entries are version pins for
        // transitive deps, which Forge expresses through a BOM or [constraints];
        // both are recorded as TODOs rather than guessed.
        Element management = firstChild(project, "dependencyManagement");
        if (management != null) {
            Element managed = firstChild(management, "dependencies");
            if (managed != null) {
                for (Element dep : children(managed, "dependency")) {
                    String scope = text(dep, "scope");
                    if ("import".equals(scope) && "pom".equals(text(dep, "type"))) {
                        String bom = coordinate(dep);
                        if (bom != null) {
                            model.addTodo("Import this BOM explicitly (AGENT.md section 9): "
                                + bom + " (see [bom]/platform import).");
                        }
                    } else {
                        String coord = coordinate(dep);
                        if (coord != null) {
                            model.addTodo("dependencyManagement pins a version for "
                                + coord + "; express it with a forced version, a BOM, "
                                + "or [resolutions] (AGENT.md sections 2bis, 9).");
                        }
                    }
                }
            }
        }
    }

    private static void addDependency(BuildModel model, Element dep) {
        String coordinate = coordinate(dep);
        if (coordinate == null) {
            return;
        }
        String scope = text(dep, "scope");
        if ("import".equals(scope) || "system".equals(scope)) {
            model.addTodo("Dependency '" + coordinate + "' uses Maven scope '"
                + scope + "', which Forge does not support; review it manually.");
            return;
        }
        model.addDependency(coordinate, text(dep, "version"), SCOPE_MAP.getOrDefault(scope, "compile"));
    }

    /** {@code groupId:artifactId[:type][:classifier]} from a &lt;dependency&gt;. */
    private static String coordinate(Element dep) {
        String groupId = text(dep, "groupId");
        String artifactId = text(dep, "artifactId");
        if (groupId == null || artifactId == null) {
            return null;
        }
        return groupId + ":" + artifactId;
    }

    private static String parentGroup(Element project) {
        Element parent = firstChild(project, "parent");
        return parent != null ? text(parent, "groupId") : null;
    }

    /**
     * Looks for a runnable main class in the common plugins. Forge's
     * {@code [exec] main} mirrors Maven's exec-maven-plugin and Shade
     * mainClass, which is what {@code forge run} consumes.
     */
    private static void detectMainClass(Element project, BuildModel model) {
        Element build = firstChild(project, "build");
        if (build == null) {
            return;
        }
        Element plugins = firstChild(build, "plugins");
        if (plugins == null) {
            return;
        }
        for (Element plugin : children(plugins, "plugin")) {
            Element configuration = firstChild(plugin, "configuration");
            if (configuration == null) {
                continue;
            }
            String main = firstNonBlank(text(configuration, "mainClass"),
                text(configuration, "mainClassName"));
            if (main != null) {
                model.setMainClass(main);
                return;
            }
        }
    }

    private static Document parse(File pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Do not fetch external DTDs/schemas: a pom must parse offline and
            // must not trigger network access during an import.
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(pom);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.IMP_0002,
                "could not parse " + pom.getName() + ": " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element parent, String name) {
        for (Element child : children(parent)) {
            if (child.getTagName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private static java.util.List<Element> children(Element parent, String name) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        for (Element child : children(parent)) {
            if (child.getTagName().equals(name)) {
                out.add(child);
            }
        }
        return out;
    }

    private static java.util.List<Element> children(Element parent) {
        java.util.List<Element> out = new java.util.ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        Element child = firstChild(parent, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
