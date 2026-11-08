package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.model.ProjectConfig;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the publishing POM generator (AGENT.md section 6). The POM must
 * be valid for Maven Central without manual edits and byte-stable across runs
 * (section 17).
 */
class PomGeneratorTest {

    private static ProjectConfig project() {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("io.example");
        config.setName("awesome");
        config.setVersion("1.2.3");
        config.setDescription("An awesome library");
        ProjectConfig.PublishSettings pub = config.getPublishSettings();
        pub.setLicenseName("Apache-2.0");
        pub.setLicenseUrl("https://www.apache.org/licenses/LICENSE-2.0");
        pub.setDeveloperIds("jdoe");
        pub.setDeveloperNames("Jane Doe");
        pub.setDeveloperEmails("jane@example.com");
        pub.setUrl("https://example.io/awesome");
        pub.setScmUrl("scm:git:https://github.com/example/awesome.git");
        return config;
    }

    @Test
    void emitsCoordinatesAndCentralMetadata() {
        String pom = PomGenerator.generate(project());

        assertTrue(pom.contains("<modelVersion>4.0.0</modelVersion>"));
        assertTrue(pom.contains("<groupId>io.example</groupId>"));
        assertTrue(pom.contains("<artifactId>awesome</artifactId>"));
        assertTrue(pom.contains("<version>1.2.3</version>"));
        assertTrue(pom.contains("<packaging>jar</packaging>"));
        assertTrue(pom.contains("<licenses>"));
        assertTrue(pom.contains("<name>Apache-2.0</name>"));
        assertTrue(pom.contains("<developers>"));
        assertTrue(pom.contains("<email>jane@example.com</email>"));
        assertTrue(pom.contains("<scm>"));
        assertTrue(pom.contains("<url>https://github.com/example/awesome.git</url>"));
    }

    @Test
    void listsDependenciesSortedByCoordinateWithScope() {
        ProjectConfig config = project();
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("org.slf4j:slf4j-api", dep("2.0.7", "compile"));
        deps.put("com.foo:bar", dep("1.0", "test"));
        config.setDependencies(deps);

        String pom = PomGenerator.generate(config);

        assertTrue(pom.contains("<dependencies>"));
        // Sorted: com.foo before org.slf4j.
        assertTrue(pom.indexOf("com.foo") < pom.indexOf("org.slf4j"));
        assertTrue(pom.contains("<scope>test</scope>"));
        // Compile scope is the POM default and is not emitted.
        assertFalse(pom.contains("<scope>compile</scope>"));
    }

    @Test
    void escapesXmlSpecialCharacters() {
        ProjectConfig config = project();
        config.setDescription("a < b & c > d");

        String pom = PomGenerator.generate(config);

        assertTrue(pom.contains("a &lt; b &amp; c &gt; d"));
        assertFalse(pom.contains("a < b & c > d"));
    }

    @Test
    void omitsOptionalSectionsWhenAbsent() {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("g");
        config.setName("a");
        config.setVersion("1");

        String pom = PomGenerator.generate(config);

        assertFalse(pom.contains("<licenses>"));
        assertFalse(pom.contains("<developers>"));
        assertFalse(pom.contains("<scm>"));
        assertFalse(pom.contains("<dependencies>"));
    }

    @Test
    void isDeterministicForTheSameConfiguration() {
        ProjectConfig config = project();
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("z:z", dep("1", "compile"));
        deps.put("a:a", dep("1", "compile"));
        config.setDependencies(deps);

        assertEquals(PomGenerator.generate(config), PomGenerator.generate(config));
    }

    private static Map<String, Object> dep(String version, String scope) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version", version);
        d.put("scope", scope);
        return d;
    }
}
