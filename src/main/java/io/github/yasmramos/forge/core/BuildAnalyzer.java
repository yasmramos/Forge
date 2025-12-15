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
import java.util.List;
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
        // TODO: Implement package structure analysis
        // This would parse package declarations and create dependency graphs
    }
    
    private void analyzeDependencies(ProjectAnalysis analysis) {
        // TODO: Implement dependency analysis
        // This would analyze import statements and create dependency graphs
    }
    
    private void analyzeTestFiles(ProjectAnalysis analysis) {
        // TODO: Implement test file analysis
        // This would identify test files and their relationships
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
        // TODO: Implement actual complexity calculation
        // This would analyze cyclomatic complexity, nesting depth, etc.
        return sourceFiles.size() * 10.0; // Simple heuristic
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