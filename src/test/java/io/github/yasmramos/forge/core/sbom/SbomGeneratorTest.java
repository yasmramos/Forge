package io.github.yasmramos.forge.core.sbom;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the CycloneDX SBOM generator (AGENT.md section 18). The
 * document must be valid and deterministic (section 17): same graph, same
 * bytes, including the serial number.
 */
class SbomGeneratorTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static SbomGenerator.Component comp(String ga, String version, String scope, String sha) {
        String[] parts = ga.split(":");
        return new SbomGenerator.Component(parts[0], parts[1], version, scope, sha);
    }

    @Test
    void emitsCycloneDxEnvelopeAndComponents() {
        String json = SbomGenerator.generate("demo", "1.2.3",
            Collections.singletonList(comp("org.slf4j:slf4j-api", "2.0.7", "compile", "aa")), T);

        assertTrue(json.contains("\"bomFormat\": \"CycloneDX\""));
        assertTrue(json.contains("\"specVersion\": \"1.5\""));
        assertTrue(json.contains("\"name\": \"demo\""));
        assertTrue(json.contains("\"version\": \"1.2.3\""));
        assertTrue(json.contains("\"purl\": \"pkg:maven/org.slf4j/slf4j-api@2.0.7\""));
        assertTrue(json.contains("\"alg\": \"SHA-256\""));
    }

    @Test
    void isDeterministicAcrossRunsAndInputOrder() {
        List<SbomGenerator.Component> a = Arrays.asList(
            comp("com.foo:bar", "1.0", "compile", "aa"),
            comp("com.foo:baz", "2.0", "test", "bb"));
        List<SbomGenerator.Component> reversed = Arrays.asList(a.get(1), a.get(0));

        String first = SbomGenerator.generate("p", "1", a, T);
        String second = SbomGenerator.generate("p", "1", reversed, T);

        assertEquals(first, second);
    }

    @Test
    void componentsAreSortedByCoordinate() {
        String json = SbomGenerator.generate("p", "1", Arrays.asList(
            comp("com.z:z", "1", "compile", "zz"),
            comp("com.a:a", "1", "compile", "aa")), T);

        assertTrue(json.indexOf("com.a") < json.indexOf("com.z"),
            "components must be sorted for stable diffs");
    }

    @Test
    void omitsHashesWhenChecksumUnknown() {
        String json = SbomGenerator.generate("p", "1",
            Collections.singletonList(comp("com.foo:bar", "1.0", "compile", null)), T);

        assertFalse(json.contains("\"hashes\""));
        assertTrue(json.contains("\"purl\": \"pkg:maven/com.foo/bar@1.0\""));
    }

    @Test
    void escapesSpecialCharactersInNames() {
        SbomGenerator.Component c = new SbomGenerator.Component("g", "a\"b\\c", "1", "compile", null);
        String json = SbomGenerator.generate("p", "1", Collections.singletonList(c), T);

        assertTrue(json.contains("a\\\"b\\\\c"));
    }

    @Test
    void serialNumberIsStableForSameContentAndChangesWithContent() {
        String base = SbomGenerator.generate("p", "1",
            Collections.singletonList(comp("com.foo:bar", "1.0", "compile", "aa")), T);
        String same = SbomGenerator.generate("p", "1",
            Collections.singletonList(comp("com.foo:bar", "1.0", "compile", "aa")), T);
        String different = SbomGenerator.generate("p", "1",
            Collections.singletonList(comp("com.foo:bar", "1.0", "compile", "bb")), T);

        assertEquals(serial(base), serial(same));
        assertFalse(serial(base).equals(serial(different)));
        assertTrue(serial(base).startsWith("urn:uuid:"));
    }

    private static String serial(String json) {
        int start = json.indexOf("\"serialNumber\": \"") + "\"serialNumber\": \"".length();
        return json.substring(start, json.indexOf('"', start));
    }
}
