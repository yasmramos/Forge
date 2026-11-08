package io.github.yasmramos.forge.core.publish;

import io.github.yasmramos.forge.model.ProjectConfig;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Gradle Module Metadata generation (AGENT.md section 6). The
 * document must be valid GMM and deterministic (section 17).
 */
class GradleModuleMetadataGeneratorTest {

    private static ProjectConfig project() {
        ProjectConfig config = new ProjectConfig();
        config.setGroup("io.example");
        config.setName("awesome");
        config.setVersion("1.2.3");
        return config;
    }

    private static Map<String, Object> dep(String version, String scope) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version", version);
        d.put("scope", scope);
        return d;
    }

    @Test
    void emitsComponentAndBothVariants() {
        String json = GradleModuleMetadataGenerator.generate(project());

        assertTrue(json.contains("\"formatVersion\": \"1.1\""));
        assertTrue(json.contains("\"group\": \"io.example\""));
        assertTrue(json.contains("\"module\": \"awesome\""));
        assertTrue(json.contains("\"version\": \"1.2.3\""));
        assertTrue(json.contains("\"apiElements\""));
        assertTrue(json.contains("\"runtimeElements\""));
        assertTrue(json.contains("awesome-1.2.3.jar"));
        assertTrue(json.contains("awesome-1.2.3.module"));
    }

    @Test
    void runtimeDependencyGoesToRuntimeVariantOnly() {
        ProjectConfig config = project();
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("com.foo:runtime-lib", dep("1.0", "runtime"));
        config.setDependencies(deps);

        String json = GradleModuleMetadataGenerator.generate(config);

        int apiIdx = json.indexOf("apiElements");
        int runtimeIdx = json.indexOf("runtimeElements");
        int depIdx = json.indexOf("runtime-lib");
        assertTrue(depIdx > runtimeIdx, "runtime dep must appear in the runtime variant");
        assertFalse(depIdx > apiIdx && depIdx < runtimeIdx,
            "runtime dep must not appear in the api variant");
    }

    @Test
    void snapshotVersionMarksIntegrationStatus() {
        ProjectConfig config = project();
        config.setVersion("1.0.0-SNAPSHOT");

        String json = GradleModuleMetadataGenerator.generate(config);

        assertTrue(json.contains("\"org.gradle.status\": \"integration\""));
    }

    @Test
    void releaseVersionMarksReleaseStatus() {
        String json = GradleModuleMetadataGenerator.generate(project());
        assertTrue(json.contains("\"org.gradle.status\": \"release\""));
    }

    @Test
    void isDeterministicForTheSameConfiguration() {
        ProjectConfig config = project();
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("z:z", dep("1", "compile"));
        deps.put("a:a", dep("1", "compile"));
        config.setDependencies(deps);

        assertEquals(GradleModuleMetadataGenerator.generate(config),
            GradleModuleMetadataGenerator.generate(config));
    }
}
