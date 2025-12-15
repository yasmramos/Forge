package io.github.yasmramos.forge.model;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Project Configuration Model for Forge Build System
 */
public class ProjectConfig {
    private String name;
    private String version;
    private String sourceDirectory;
    private String outputDirectory;
    private List<String> sourcePaths;
    private Map<String, Object> dependencies;
    private Map<String, Object> plugins;
    private BuildSettings buildSettings;
    
    public ProjectConfig() {
        this.sourcePaths = new ArrayList<>();
        this.buildSettings = new BuildSettings();
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getSourceDirectory() {
        return sourceDirectory;
    }
    
    public void setSourceDirectory(String sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }
    
    public String getOutputDirectory() {
        return outputDirectory;
    }
    
    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }
    
    public List<String> getSourcePaths() {
        return sourcePaths;
    }
    
    public void setSourcePaths(List<String> sourcePaths) {
        this.sourcePaths = sourcePaths;
    }
    
    public Map<String, Object> getDependencies() {
        return dependencies;
    }
    
    public void setDependencies(Map<String, Object> dependencies) {
        this.dependencies = dependencies;
    }
    
    public Map<String, Object> getPlugins() {
        return plugins;
    }
    
    public void setPlugins(Map<String, Object> plugins) {
        this.plugins = plugins;
    }
    
    public BuildSettings getBuildSettings() {
        return buildSettings;
    }
    
    public void setBuildSettings(BuildSettings buildSettings) {
        this.buildSettings = buildSettings;
    }
    
    public static class BuildSettings {
        private boolean incremental = true;
        private boolean parallel = true;
        private boolean cacheEnabled = true;
        private int threads = Runtime.getRuntime().availableProcessors();
        private String compiler = "javac";
        private String encoding = "UTF-8";
        
        public boolean isIncremental() {
            return incremental;
        }
        
        public void setIncremental(boolean incremental) {
            this.incremental = incremental;
        }
        
        public boolean isParallel() {
            return parallel;
        }
        
        public void setParallel(boolean parallel) {
            this.parallel = parallel;
        }
        
        public boolean isCacheEnabled() {
            return cacheEnabled;
        }
        
        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }
        
        public int getThreads() {
            return threads;
        }
        
        public void setThreads(int threads) {
            this.threads = threads;
        }
        
        public String getCompiler() {
            return compiler;
        }
        
        public void setCompiler(String compiler) {
            this.compiler = compiler;
        }
        
        public String getEncoding() {
            return encoding;
        }
        
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
    }
}