package io.github.yasmramos.forge.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project Analysis result containing project structure and metrics
 */
public class ProjectAnalysis {
    private List<Path> sourceFiles;
    private List<Path> testFiles;
    private List<Path> resourceFiles;
    private List<Path> changedSources;
    private int totalLinesOfCode;
    private double complexityScore;
    private long estimatedBuildTime;
    private Map<String, Integer> packageMetrics;
    
    public ProjectAnalysis() {
        this.sourceFiles = new ArrayList<>();
        this.testFiles = new ArrayList<>();
        this.resourceFiles = new ArrayList<>();
        this.changedSources = new ArrayList<>();
        this.packageMetrics = new HashMap<>();
    }
    
    public void addSourceFiles(List<Path> files) {
        sourceFiles.addAll(files);
    }
    
    public List<Path> getSourceFiles() {
        return sourceFiles;
    }
    
    public List<Path> getTestFiles() {
        return testFiles;
    }
    
    public List<Path> getResourceFiles() {
        return resourceFiles;
    }
    
    public List<Path> getChangedSources() {
        return changedSources;
    }
    
    public void setChangedSources(List<Path> changedSources) {
        this.changedSources = changedSources;
    }
    
    public boolean hasChanges() {
        return !changedSources.isEmpty();
    }
    
    public int getTotalLinesOfCode() {
        return totalLinesOfCode;
    }
    
    public void setTotalLinesOfCode(int totalLinesOfCode) {
        this.totalLinesOfCode = totalLinesOfCode;
    }
    
    public double getComplexityScore() {
        return complexityScore;
    }
    
    public void setComplexityScore(double complexityScore) {
        this.complexityScore = complexityScore;
    }
    
    public long getEstimatedBuildTime() {
        return estimatedBuildTime;
    }
    
    public void setEstimatedBuildTime(long estimatedBuildTime) {
        this.estimatedBuildTime = estimatedBuildTime;
    }
    
    public Map<String, Integer> getPackageMetrics() {
        return packageMetrics;
    }
    
    public void addPackageMetric(String packageName, int fileCount) {
        packageMetrics.put(packageName, fileCount);
    }
}