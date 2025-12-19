package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyType;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for DependencyResolver
 */
class DependencyResolverTest {
    
    private DependencyResolver dependencyResolver;
    private ProjectAnalysis mockAnalysis;
    
    @BeforeEach
    void setUp() {
        dependencyResolver = new DependencyResolver();
        mockAnalysis = new ProjectAnalysis();
    }
    
    @Test
    void testResolveSimpleMavenDependency() {
        Map<String, Object> dependencies = new HashMap<>();
        dependencies.put("junit:junit", "4.13.2");
        
        DependencyResolution resolution = dependencyResolver.resolve(dependencies, mockAnalysis);
        
        assertNotNull(resolution);
        assertFalse(resolution.hasErrors());
        assertEquals(1, resolution.getSuccessCount());
        assertEquals(0, resolution.getErrorCount());
    }
    
    @Test
    void testResolveMultipleDependencies() {
        Map<String, Object> dependencies = new HashMap<>();
        dependencies.put("junit:junit", "4.13.2");
        dependencies.put("org.slf4j:slf4j-api", "1.7.36");
        dependencies.put("ch.qos.logback:logback-classic", "1.2.12");
        
        DependencyResolution resolution = dependencyResolver.resolve(dependencies, mockAnalysis);
        
        assertNotNull(resolution);
        assertEquals(3, resolution.getSuccessCount());
        assertFalse(resolution.hasErrors());
    }
    
    @Test
    void testResolveWithInvalidConfig() {
        Map<String, Object> dependencies = new HashMap<>();
        dependencies.put("invalid:dependency", new Object()); // Invalid config object
        
        DependencyResolution resolution = dependencyResolver.resolve(dependencies, mockAnalysis);
        
        assertNotNull(resolution);
        assertEquals(0, resolution.getSuccessCount());
        assertTrue(resolution.hasErrors());
        assertEquals(1, resolution.getErrorCount());
    }
    
    @Test
    void testResolveWithEmptyDependencies() {
        Map<String, Object> dependencies = new HashMap<>();
        
        DependencyResolution resolution = dependencyResolver.resolve(dependencies, mockAnalysis);
        
        assertNotNull(resolution);
        assertEquals(0, resolution.getSuccessCount());
        assertEquals(0, resolution.getErrorCount());
        assertFalse(resolution.hasErrors());
    }
    
    @Test
    void testDependencyInfoProperties() {
        DependencyInfo info = new DependencyInfo("test-group", "1.0.0", DependencyType.MAVEN);
        
        assertEquals("test-group", info.getName());
        assertEquals("1.0.0", info.getVersion());
        assertEquals(DependencyType.MAVEN, info.getType());
        assertTrue(info.isResolved());
        assertNotNull(info.getLocalPath());
        assertTrue(info.getLocalPath().toString().contains("test-group"));
    }
    
    @Test
    void testDependencyResolutionResult() {
        DependencyResolution resolution = new DependencyResolution();
        
        assertEquals(0, resolution.getSuccessCount());
        assertEquals(0, resolution.getErrorCount());
        assertFalse(resolution.hasErrors());
        
        DependencyInfo dep = new DependencyInfo("test", "1.0", DependencyType.LOCAL);
        resolution.addDependency(dep);
        
        assertEquals(1, resolution.getSuccessCount());
        assertFalse(resolution.hasErrors());
        
        resolution.addError("broken", "test error");
        
        assertEquals(1, resolution.getErrorCount());
        assertTrue(resolution.hasErrors());
    }
}