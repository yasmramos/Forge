package io.github.yasmramos.forge.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scope rules used to build the compile / run / test classpaths (AGENT.md section 2). */
class DependencyScopesTest {

    private Map<String, Object> declared() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("org.slf4j:slf4j-api", entry("2.0.7", "compile"));
        deps.put("javax.servlet:servlet-api", entry("4.0.1", "provided"));
        deps.put("ch.qos.logback:logback-classic", entry("1.4.11", "runtime"));
        deps.put("org.junit.jupiter:junit-jupiter", entry("5.9.2", "test"));
        return deps;
    }

    private Map<String, Object> entry(String version, String scope) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("version", version);
        e.put("type", "maven");
        e.put("scope", scope);
        return e;
    }

    @Test
    void compileClasspathExcludesTestAndRuntime() {
        Map<String, Object> compile = DependencyScopes.filter(declared(), DependencyScopes.COMPILE);
        assertTrue(compile.containsKey("org.slf4j:slf4j-api"));
        assertTrue(compile.containsKey("javax.servlet:servlet-api"), "provided is compile-visible");
        assertFalse(compile.containsKey("ch.qos.logback:logback-classic"), "runtime excluded");
        assertFalse(compile.containsKey("org.junit.jupiter:junit-jupiter"), "test excluded");
    }

    @Test
    void runClasspathAddsRuntimeButNotTest() {
        Map<String, Object> run = DependencyScopes.filter(declared(), DependencyScopes.RUNTIME);
        assertTrue(run.containsKey("ch.qos.logback:logback-classic"));
        assertFalse(run.containsKey("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void testClasspathIncludesEverything() {
        assertEquals(4, DependencyScopes.filter(declared(), DependencyScopes.TEST).size());
    }

    @Test
    void scopeAndVersionDefaultSafely() {
        assertEquals("compile", DependencyScopes.scopeOf("1.0.0"));
        assertEquals("1.0.0", DependencyScopes.versionOf("1.0.0"));
        assertEquals("compile", DependencyScopes.scopeOf(null));
        assertEquals(null, DependencyScopes.versionOf(null));
    }
}
