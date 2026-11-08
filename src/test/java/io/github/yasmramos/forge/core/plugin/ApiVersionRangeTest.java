package io.github.yasmramos.forge.core.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the plugin API version range (AGENT.md section 4bis). */
class ApiVersionRangeTest {

    @Test
    void parsesAndAcceptsWithinRange() {
        ApiVersionRange r = ApiVersionRange.parse(">=0.3 <1.0");
        assertTrue(r.accepts(ApiVersion.parse("0.3")));
        assertTrue(r.accepts(ApiVersion.parse("0.9.5")));
        assertFalse(r.accepts(ApiVersion.parse("0.2")));
        assertFalse(r.accepts(ApiVersion.parse("1.0")));
    }

    @Test
    void exactVersionAndBareVersion() {
        assertTrue(ApiVersionRange.parse("=1.2.3").accepts(ApiVersion.parse("1.2.3")));
        assertTrue(ApiVersionRange.parse("1.2.3").accepts(ApiVersion.parse("1.2.3")));
        assertFalse(ApiVersionRange.parse("1.2.3").accepts(ApiVersion.parse("1.2.4")));
    }

    @Test
    void wildcardAcceptsAnything() {
        ApiVersionRange r = ApiVersionRange.parse("*");
        assertTrue(r.accepts(ApiVersion.parse("0.0.1")));
        assertTrue(r.accepts(ApiVersion.parse("9.9.9")));
    }

    @Test
    void commaSeparatedClauses() {
        ApiVersionRange r = ApiVersionRange.parse(">=0.1, <0.5");
        assertTrue(r.accepts(ApiVersion.parse("0.4.9")));
        assertFalse(r.accepts(ApiVersion.parse("0.5")));
    }

    @Test
    void preReleaseAndBuildMetadataAreIgnoredForOrdering() {
        assertEquals(ApiVersion.parse("1.2.3"), ApiVersion.parse("1.2.3-rc1"));
        assertEquals(ApiVersion.parse("1.2.3"), ApiVersion.parse("1.2.3+build.7"));
    }

    @Test
    void malformedInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> ApiVersionRange.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ApiVersionRange.parse(">="));
        assertThrows(IllegalArgumentException.class, () -> ApiVersionRange.parse(">=x.y"));
        assertThrows(IllegalArgumentException.class, () -> ApiVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ApiVersion.parse("1.2.3.4"));
    }
}
