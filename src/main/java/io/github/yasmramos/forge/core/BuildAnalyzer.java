package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Build Analyzer for Forge Build System
 * Analyzes project structure, source files, and changes for optimization
 */
public class BuildAnalyzer {
    
    private final Logger logger = LoggerFactory.getLogger(BuildAnalyzer.class);
    
    public ProjectAnalysis analyzeProject(ProjectConfig config) {
        logger.info("📊 Analyzing project structure...");
        
        ProjectAnalysis analysis = new ProjectAnalysis();
        
        try {
            // Analyze source directories
            List<Path> sourceDirectories = getSourceDirectories(config);
            
            for (Path sourceDir : sourceDirectories) {
                if (Files.exists(sourceDir)) {
                    List<Path> sourceFiles = findJavaSourceFiles(sourceDir);
                    analysis.addSourceFiles(sourceFiles);
                    
                    logger.debug("📁 Found " + sourceFiles.size() + " source files in: " + sourceDir);
                } else {
                    logger.warn("⚠️  Source directory not found: " + sourceDir);
                }
            }
            
            // Analyze project structure
            analyzeProjectStructure(analysis);
            
            // Calculate project metrics
            calculateMetrics(analysis);
            
        } catch (Exception e) {
            logger.error("Failed to analyze project", e);
        }
        
        return analysis;
    }
    
    public ProjectAnalysis analyzeChanges(ProjectConfig config) {
        logger.info("🔍 Analyzing project changes...");
        
        ProjectAnalysis analysis = new ProjectAnalysis();
        
        try {
            // Get last build timestamp
            long lastBuildTime = getLastBuildTime();
            
            // Find changed files since last build
            List<Path> sourceDirectories = getSourceDirectories(config);
            
            for (Path sourceDir : sourceDirectories) {
                if (Files.exists(sourceDir)) {
                    List<Path> changedFiles = findChangedFiles(sourceDir, lastBuildTime);
                    analysis.setChangedSources(changedFiles);
                    
                    if (!changedFiles.isEmpty()) {
                        logger.info("🔄 Found " + changedFiles.size() + " changed files");
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to analyze changes", e);
        }
        
        return analysis;
    }
    
    private List<Path> getSourceDirectories(ProjectConfig config) {
        List<Path> directories = new ArrayList<>();
        
        // Use configured source directory
        if (config.getSourceDirectory() != null) {
            directories.add(Paths.get(config.getSourceDirectory()));
        }
        
        // Add configured source paths
        if (config.getSourcePaths() != null) {
            for (String sourcePath : config.getSourcePaths()) {
                directories.add(Paths.get(sourcePath));
            }
        }
        
        // Default source directories
        if (directories.isEmpty()) {
            directories.add(Paths.get("src/main/java"));
            directories.add(Paths.get("src/test/java"));
        }
        
        return directories;
    }
    
    private List<Path> findJavaSourceFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .collect(ArrayList::new, (list, path) -> list.add(path), ArrayList::addAll);
        }
    }
    
    private List<Path> findChangedFiles(Path directory, long sinceTimestamp) throws IOException {
        List<Path> changedFiles = new ArrayList<>();
        
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(file) && file.toString().endsWith(".java")) {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toMillis() > sinceTimestamp) {
                        changedFiles.add(file);
                    }
                }
            }
        }
        
        return changedFiles;
    }
    
    private void analyzeProjectStructure(ProjectAnalysis analysis) {
        // Analyze package structure
        analyzePackageStructure(analysis);
        
        // Analyze dependencies between files
        analyzeDependencies(analysis);
        
        // Analyze test files
        analyzeTestFiles(analysis);
    }
    
    private void analyzePackageStructure(ProjectAnalysis analysis) {
        logger.debug("📦 Analyzing package structure...");
        
        Map<String, Integer> packageFileCount = new HashMap<>();
        
        for (Path sourceFile : analysis.getSourceFiles()) {
            try {
                String packageName = extractPackageName(sourceFile);
                if (packageName != null) {
                    packageFileCount.merge(packageName, 1, Integer::sum);
                    analysis.addPackageMetric(packageName, 
                        packageFileCount.get(packageName));
                }
            } catch (IOException e) {
                logger.debug("Failed to analyze package for file: " + sourceFile, e);
            }
        }
        
        logger.debug("📦 Package structure analysis complete: " + 
                    packageFileCount.size() + " packages found");
    }
    
    private void analyzeDependencies(ProjectAnalysis analysis) {
        logger.debug("🔗 Analyzing source file dependencies...");
        
        Map<String, Set<String>> fileDependencies = new HashMap<>();
        
        for (Path sourceFile : analysis.getSourceFiles()) {
            try {
                String fileName = sourceFile.getFileName().toString();
                Set<String> imports = extractImports(sourceFile);
                fileDependencies.put(fileName, imports);
                
                if (!imports.isEmpty()) {
                    logger.debug("📋 File " + fileName + " imports: " + imports.size() + " dependencies");
                }
            } catch (IOException e) {
                logger.debug("Failed to analyze dependencies for file: " + sourceFile, e);
            }
        }
        
        logger.debug("🔗 Dependency analysis complete: " + 
                    fileDependencies.size() + " files analyzed");
    }
    
    private void analyzeTestFiles(ProjectAnalysis analysis) {
        logger.debug("🧪 Analyzing test files...");
        
        for (Path sourceFile : analysis.getSourceFiles()) {
            String fileName = sourceFile.getFileName().toString().toLowerCase();
            
            // Check if it's a test file
            boolean isTestFile = fileName.contains("test") || 
                               fileName.contains("spec") ||
                               sourceFile.getParent() != null && 
                               sourceFile.getParent().toString().contains("test");
            
            if (isTestFile) {
                analysis.getTestFiles().add(sourceFile);
                logger.debug("🧪 Found test file: " + sourceFile);
            }
        }
        
        logger.debug("🧪 Test analysis complete: " + 
                    analysis.getTestFiles().size() + " test files found");
    }
    
    private void calculateMetrics(ProjectAnalysis analysis) {
        // Calculate total lines of code
        analysis.setTotalLinesOfCode(calculateTotalLinesOfCode(analysis.getSourceFiles()));
        
        // Calculate complexity metrics
        analysis.setComplexityScore(calculateComplexityScore(analysis.getSourceFiles()));
        
        // Estimate build time
        analysis.setEstimatedBuildTime(estimateBuildTime(analysis));
    }
    
    private int calculateTotalLinesOfCode(List<Path> sourceFiles) {
        int totalLines = 0;
        
        for (Path sourceFile : sourceFiles) {
            try {
                totalLines += Files.lines(sourceFile).count();
            } catch (IOException e) {
                logger.debug("Failed to count lines in: " + sourceFile, e);
            }
        }
        
        return totalLines;
    }
    
    private double calculateComplexityScore(List<Path> sourceFiles) {
        logger.debug("📊 Calculating complexity score for " + sourceFiles.size() + " files...");
        
        double totalComplexity = 0.0;
        
        for (Path sourceFile : sourceFiles) {
            try {
                String content = Files.readString(sourceFile);
                double fileComplexity = analyzeFileComplexity(content);
                totalComplexity += fileComplexity;
            } catch (IOException e) {
                logger.debug("Failed to calculate complexity for: " + sourceFile, e);
            }
        }
        
        logger.debug("📊 Complexity calculation complete: " + totalComplexity);
        return totalComplexity;
    }
    
    private double analyzeFileComplexity(String content) {
        // Simple complexity calculation based on various factors
        int methodCount = countOccurrences(content, "public ") + 
                         countOccurrences(content, "private ") + 
                         countOccurrences(content, "protected ");
        
        int loopCount = countOccurrences(content, "for (") + 
                       countOccurrences(content, "while (") + 
                       countOccurrences(content, "do {");
        
        int ifCount = countOccurrences(content, "if (") + 
                     countOccurrences(content, "else if (") + 
                     countOccurrences(content, "switch (");
        
        int tryCount = countOccurrences(content, "try {");
        
        // Weighted complexity score
        double complexity = methodCount * 2.0 + loopCount * 3.0 + ifCount * 2.0 + tryCount * 1.5;
        return complexity;
    }
    
    private String extractPackageName(Path sourceFile) throws IOException {
        try {
            String content = Files.readString(sourceFile);
            String[] lines = content.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("package ")) {
                    return line.substring(8, line.length() - 1).trim();
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to extract package name from: " + sourceFile, e);
        }
        return null;
    }
    
    private Set<String> extractImports(Path sourceFile) throws IOException {
        Set<String> imports = new HashSet<>();
        
        try {
            String content = Files.readString(sourceFile);
            String[] lines = content.split("\n");
            
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("import ")) {
                    String importStatement = line.substring(7, line.length() - 1).trim();
                    imports.add(importStatement);
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to extract imports from: " + sourceFile, e);
        }
        
        return imports;
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
    
    private long estimateBuildTime(ProjectAnalysis analysis) {
        // Estimate based on file count and complexity
        long baseTime = analysis.getSourceFiles().size() * 50; // 50ms per file base
        long complexityTime = (long) (analysis.getComplexityScore() * 10);
        
        return baseTime + complexityTime;
    }
    
    private long getLastBuildTime() {
        // TODO: Implement actual last build time tracking
        // This would read from build metadata or cache timestamps
        return 0; // For now, assume no previous build
    }
}