package io.github.yasmramos.forge.core.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build-system-neutral model produced by {@code forge import} (AGENT.md section
 * 14). Maven and Gradle importers both fill this in, and a single writer turns
 * it into {@code forge.toml}.
 *
 * <p>The point of the intermediate step is that "how to read a pom.xml" and "how
 * to write forge.toml" never mix: adding a build system means adding one
 * importer, not a new writer.</p>
 */
public final class BuildModel {

    /** One declared dependency: a Maven coordinate, its version and Forge scope. */
    public static final class Dependency {
        public final String coordinate;
        /** Declared version, a {@code ${...}} reference, or null when unversioned. */
        public final String version;
        /** Forge scope: compile, runtime, test or provided. */
        public final String scope;

        public Dependency(String coordinate, String version, String scope) {
            this.coordinate = coordinate;
            this.version = version;
            this.scope = scope;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Dependency)) {
                return false;
            }
            Dependency other = (Dependency) o;
            return java.util.Objects.equals(coordinate, other.coordinate)
                && java.util.Objects.equals(version, other.version)
                && java.util.Objects.equals(scope, other.scope);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(coordinate, version, scope);
        }
    }

    private String name;
    private String version = "1.0.0";
    private String group;
    private String packaging = "jar";
    private String description;
    private String language;
    /** Reusable ${...} properties, in declaration order. */
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<String, String> repositories = new LinkedHashMap<>();
    private final List<Dependency> dependencies = new ArrayList<>();
    private final List<String> mainJavaSources = new ArrayList<>();
    private final List<String> testJavaSources = new ArrayList<>();
    private String mainClass;
    /**
     * Things the importer could not translate. They become {@code TODO} comments
     * in the generated file so the gap is visible instead of silent (section 14:
     * "reasonable, not perfect").
     */
    private final List<String> todos = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Map<String, String> getProperties() { return properties; }
    public Map<String, String> getRepositories() { return repositories; }
    public List<Dependency> getDependencies() { return dependencies; }

    public List<String> getMainJavaSources() { return mainJavaSources; }
    public List<String> getTestJavaSources() { return testJavaSources; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public List<String> getTodos() { return todos; }

    /** Adds a dependency, ignoring null/blank coordinates. */
    public void addDependency(String coordinate, String scope) {
        addDependency(coordinate, null, scope);
    }

    /** Adds a dependency with its declared version, ignoring blank coordinates. */
    public void addDependency(String coordinate, String version, String scope) {
        if (coordinate != null && !coordinate.trim().isEmpty()) {
            dependencies.add(new Dependency(coordinate.trim(),
                version == null || version.trim().isEmpty() ? null : version.trim(), scope));
        }
    }

    /** Records a migration gap rendered as a TODO comment. */
    public void addTodo(String todo) {
        if (todo != null && !todo.trim().isEmpty()) {
            todos.add(todo.trim());
        }
    }
}
