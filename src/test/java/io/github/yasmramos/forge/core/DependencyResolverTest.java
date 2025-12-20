package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for DependencyResolver
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
    void testDependencyResolverCreation() {
        assertNotNull(dependencyResolver);
    }
    
    @Test
    void testResolveMethodExists() {
        Map<String, Object> dependencies = new HashMap<>();
        DependencyResolution result = dependencyResolver.resolve(dependencies, mockAnalysis);
        assertNotNull(result);
    }
}