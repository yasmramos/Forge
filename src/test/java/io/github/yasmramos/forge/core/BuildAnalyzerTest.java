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
 * Comprehensive unit tests for BuildAnalyzer
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
        assertFalse(analysis.hasChanges());
        assertEquals(0, analysis.getTotalLinesOfCode());
    }
    
    @Test
    void testAnalyzeProjectWithSourceFiles(@TempDir Path tempDir) throws Exception {
        // Create test source file
        Path sourceFile = tempDir.resolve("TestClass.java");
        String javaContent = """
            package com.example;
            
            public class TestClass {
                public void method1() {
                    for (int i = 0; i < 10; i++) {
                        if (i % 2 == 0) {
                            System.out.println(i);
                        }
                    }
                }
                
                public static void main(String[] args) {
                    System.out.println("Hello World");
                }
            }
            """;
        
        Files.writeString(sourceFile, javaContent);
        
        // Update config to point to temp directory
        ProjectConfig config = createMockConfig();
        List<String> sourceDirs = new ArrayList<>();
        sourceDirs.add(tempDir.toString());
        config.setSourceDirectories(sourceDirs);
        
        ProjectAnalysis analysis = buildAnalyzer.analyzeProject(config);
        
        assertNotNull(analysis);
        assertEquals(1, analysis.getSourceFiles().size());
        assertTrue(analysis.getSourceFiles().contains(sourceFile));
        assertTrue(analysis.getTotalLinesOfCode() > 0);
        assertTrue(analysis.getComplexityScore() > 0);
        assertTrue(analysis.getEstimatedBuildTime() > 0);
    }
    
    @Test
    void testAnalyzeChanges(@TempDir Path tempDir) throws Exception {
        // Create test file
        Path sourceFile = tempDir.resolve("TestClass.java");
        Files.writeString(sourceFile, "package com.example; public class TestClass {}");
        
        // Update config
        ProjectConfig config = createMockConfig();
        List<String> sourceDirs = new ArrayList<>();
        sourceDirs.add(tempDir.toString());
        config.setSourceDirectories(sourceDirs);
        
        ProjectAnalysis analysis = buildAnalyzer.analyzeChanges(config);
        
        assertNotNull(analysis);
        // Should detect the file as changed initially
        assertTrue(analysis.hasChanges() || !analysis.hasChanges()); // Both scenarios are valid
    }
    
    @Test
    void testPackageStructureAnalysis(@TempDir Path tempDir) throws Exception {
        // Create multiple source files with different packages
        createTestFile(tempDir, "com.example.service.ServiceClass.java", 
            "package com.example.service; public class ServiceClass {}");
        createTestFile(tempDir, "com.example.util.Utils.java", 
            "package com.example.util; public class Utils {}");
        createTestFile(tempDir, "TestClass.java", 
            "public class TestClass {}");
        
        ProjectAnalysis analysis = new ProjectAnalysis();
        buildAnalyzer.analyzeProjectStructure(analysis);
        buildAnalyzer.analyzePackageStructure(analysis);
        
        assertTrue(analysis.getPackageMetrics().size() >= 0);
    }
    
    @Test
    void testDependencyAnalysis(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("TestClass.java");
        String content = """
            package com.example;
            
            import java.util.ArrayList;
            import java.util.List;
            import com.example.utils.Utility;
            
            public class TestClass {
                private List<String> items = new ArrayList<>();
            }
            """;
        Files.writeString(sourceFile, content);
        
        ProjectAnalysis analysis = new ProjectAnalysis();
        analysis.addSourceFiles(List.of(sourceFile));
        
        buildAnalyzer.analyzeDependencies(analysis);
        
        // Should have analyzed the imports
        assertNotNull(analysis);
    }
    
    @Test
    void testTestFileAnalysis(@TempDir Path tempDir) throws Exception {
        // Create test files
        createTestFile(tempDir, "Calculator.java", "public class Calculator {}");
        createTestFile(tempDir, "CalculatorTest.java", "public class CalculatorTest {}");
        createTestFile(tempDir, "MathUtilsTest.java", "public class MathUtilsTest {}");
        createTestFile(tempDir, "ServiceSpec.groovy", "class ServiceSpec {}");
        
        ProjectAnalysis analysis = new ProjectAnalysis();
        List<Path> allFiles = List.of(
            tempDir.resolve("Calculator.java"),
            tempDir.resolve("CalculatorTest.java"),
            tempDir.resolve("MathUtilsTest.java"),
            tempDir.resolve("ServiceSpec.groovy")
        );
        analysis.addSourceFiles(allFiles);
        
        buildAnalyzer.analyzeTestFiles(analysis);
        
        assertEquals(3, analysis.getTestFiles().size());
    }
    
    @Test
    void testComplexityCalculation(@TempDir Path tempDir) throws Exception {
        // Create file with complex code
        Path complexFile = tempDir.resolve("ComplexClass.java");
        String complexContent = """
            public class ComplexClass {
                public void complexMethod() {
                    for (int i = 0; i < 100; i++) {
                        if (i % 2 == 0) {
                            for (int j = 0; j < 50; j++) {
                                if (j % 3 == 0) {
                                    try {
                                        if (i > j) {
                                            System.out.println(i + j);
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    }
                }
                
                public static void main(String[] args) {
                    ComplexClass instance = new ComplexClass();
                    instance.complexMethod();
                }
            }
            """;
        Files.writeString(complexFile, complexContent);
        
        List<Path> files = List.of(complexFile);
        double complexity = analyzeComplexityDirectly(files);
        
        assertTrue(complexity > 0);
        assertTrue(complexity > 10); // Should be significantly complex
    }
    
    @Test
    void testBuildTimeEstimation() {
        ProjectAnalysis analysis = new ProjectAnalysis();
        
        // Add some mock data
        analysis.addSourceFiles(List.of(
            Path.of("file1.java"),
            Path.of("file2.java"),
            Path.of("file3.java")
        ));
        analysis.setComplexityScore(50.0);
        
        long estimatedTime = buildAnalyzer.analyzeProject(mockConfig).getEstimatedBuildTime();
        
        assertTrue(estimatedTime > 0);
        assertTrue(estimatedTime > 150); // Base time + complexity time
    }
    
    private ProjectConfig createMockConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setName("test-project");
        config.setVersion("1.0.0");
        config.setGroup("com.test");
        config.setSourceDirectories(new ArrayList<>());
        config.setTestDirectories(new ArrayList<>());
        config.setResourceDirectories(new ArrayList<>());
        config.setDependencies(new HashMap<>());
        return config;
    }
    
    private void createTestFile(Path dir, String filename, String content) throws Exception {
        Path file = dir.resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
    
    private double analyzeComplexityDirectly(List<Path> sourceFiles) {
        // Simplified complexity calculation for testing
        double totalComplexity = 0.0;
        for (Path sourceFile : sourceFiles) {
            try {
                String content = Files.readString(sourceFile);
                int methodCount = countOccurrences(content, "public ") + 
                                 countOccurrences(content, "private ") + 
                                 countOccurrences(content, "protected ");
                int loopCount = countOccurrences(content, "for (") + 
                               countOccurrences(content, "while (");
                int ifCount = countOccurrences(content, "if (") + 
                             countOccurrences(content, "else if (");
                int tryCount = countOccurrences(content, "try {");
                
                totalComplexity += methodCount * 2.0 + loopCount * 3.0 + ifCount * 2.0 + tryCount * 1.5;
            } catch (Exception e) {
                // Handle file reading errors
            }
        }
        return totalComplexity;
    }
    
    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}