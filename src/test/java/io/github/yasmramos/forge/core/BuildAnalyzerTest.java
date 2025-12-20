package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectAnalysis;
import io.github.yasmramos.forge.model.ProjectConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple unit tests for BuildAnalyzer
 */
class BuildAnalyzerTest {
    
    private BuildAnalyzer buildAnalyzer;
    private ProjectConfig mockConfig;
    
    @BeforeEach
    void setUp() {
        buildAnalyzer = new BuildAnalyzer();
        mockConfig = createMockConfig();
    }
    
    @Test
    void testAnalyzeEmptyProject() {
        ProjectAnalysis analysis = buildAnalyzer.analyzeProject(mockConfig);
        
        assertNotNull(analysis);
        assertNotNull(analysis.getSourceFiles());
        assertTrue(analysis.getSourceFiles().isEmpty());
    }
    
    @Test
    void testAnalyzeProjectWithSourceFiles(@TempDir Path tempDir) throws Exception {
        // Create test source file
        Path sourceFile = tempDir.resolve("TestClass.java");
        String javaContent = "package com.example;\n" +
            "\n" +
            "public class TestClass {\n" +
            "    public void method1() {\n" +
            "        for (int i = 0; i < 10; i++) {\n" +
            "            System.out.println(\"Iteration: \" + i);\n" +
            "        }\n" +
            "    }\n" +
            "}\n";
        
        Files.writeString(sourceFile, javaContent);
        
        // Add source path to config
        List<String> sourcePaths = new ArrayList<>();
        sourcePaths.add(tempDir.toString());
        mockConfig.setSourcePaths(sourcePaths);
        
        ProjectAnalysis analysis = buildAnalyzer.analyzeProject(mockConfig);
        
        assertNotNull(analysis);
        assertFalse(analysis.getSourceFiles().isEmpty());
        assertEquals(1, analysis.getSourceFiles().size());
    }
    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("test-project");
        config.setVersion("1.0.0");
        // Use non-existent source paths to ensure no files are found
        List<String> emptySourcePaths = new ArrayList<>();
        emptySourcePaths.add("/non/existent/path");
        emptySourcePaths.add("/another/non/existent/path");
        config.setSourcePaths(emptySourcePaths);
        return config;
    }
}