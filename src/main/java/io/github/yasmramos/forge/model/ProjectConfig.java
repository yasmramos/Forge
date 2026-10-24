package io.github.yasmramos.forge.model;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Project Configuration Model for Forge Build System.
 *
 * <p>Populated from a {@code forge.toml} file (parsed with NightConfig).
 * The model keeps the legacy flat fields ({@code sourcePaths},
 * {@code dependencies}) for backward compatibility with the build engine,
 * and adds the TOML-schema fields: scoped sources, toolchain, repositories,
 * compile options and exec configuration.</p>
 */
public class ProjectConfig {
    private String schemaVersion;
    private String name;
    private String version;
    private String group;
    private String description;
    private String sourceDirectory;
    private String outputDirectory = "target/classes";
    private List<String> sourcePaths;
    private List<String> mainJavaSources = new ArrayList<>();
    private List<String> mainResourcePaths = new ArrayList<>();
    private List<String> testJavaSources = new ArrayList<>();
    private List<String> testResourcePaths = new ArrayList<>();
    private Map<String, String> properties = new LinkedHashMap<>();
    private Map<String, String> modules = new LinkedHashMap<>();
    private Map<String, String> repositories = new LinkedHashMap<>();
    private String sourceCompatibility;
    private String targetCompatibility;
    private String jdkVendor;
    private String jdkVersion;
    private Map<String, Object> dependencies;
    private Map<String, Object> plugins;
    private List<String> compilerArgs = new ArrayList<>();
    private String mainClass;
    private List<String> execArgs = new ArrayList<>();
    private List<String> jvmArgs = new ArrayList<>();
    private BuildSettings buildSettings;

    public ProjectConfig() {
        this.sourcePaths = new ArrayList<>();
        this.dependencies = new LinkedHashMap<>();
        this.plugins = new LinkedHashMap<>();
        this.buildSettings = new BuildSettings();
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties != null ? properties : new LinkedHashMap<>();
    }

    public Map<String, String> getModules() {
        return modules;
    }

    public void setModules(Map<String, String> modules) {
        this.modules = modules != null ? modules : new LinkedHashMap<>();
    }

    public Map<String, String> getRepositories() {
        return repositories;
    }

    public void setRepositories(Map<String, String> repositories) {
        this.repositories = repositories != null ? repositories : new LinkedHashMap<>();
    }

    public List<String> getMainJavaSources() {
        return mainJavaSources;
    }

    public void setMainJavaSources(List<String> mainJavaSources) {
        this.mainJavaSources = mainJavaSources != null ? mainJavaSources : new ArrayList<>();
    }

    public List<String> getMainResourcePaths() {
        return mainResourcePaths;
    }

    public void setMainResourcePaths(List<String> mainResourcePaths) {
        this.mainResourcePaths = mainResourcePaths != null ? mainResourcePaths : new ArrayList<>();
    }

    public List<String> getTestJavaSources() {
        return testJavaSources;
    }

    public void setTestJavaSources(List<String> testJavaSources) {
        this.testJavaSources = testJavaSources != null ? testJavaSources : new ArrayList<>();
    }

    public List<String> getTestResourcePaths() {
        return testResourcePaths;
    }

    public void setTestResourcePaths(List<String> testResourcePaths) {
        this.testResourcePaths = testResourcePaths != null ? testResourcePaths : new ArrayList<>();
    }

    public String getSourceCompatibility() {
        return sourceCompatibility;
    }

    public void setSourceCompatibility(String sourceCompatibility) {
        this.sourceCompatibility = sourceCompatibility;
    }

    public String getTargetCompatibility() {
        return targetCompatibility;
    }

    public void setTargetCompatibility(String targetCompatibility) {
        this.targetCompatibility = targetCompatibility;
    }

    public String getJdkVendor() {
        return jdkVendor;
    }

    public void setJdkVendor(String jdkVendor) {
        this.jdkVendor = jdkVendor;
    }

    public String getJdkVersion() {
        return jdkVersion;
    }

    public void setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    public List<String> getCompilerArgs() {
        return compilerArgs;
    }

    public void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs != null ? compilerArgs : new ArrayList<>();
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public List<String> getExecArgs() {
        return execArgs;
    }

    public void setExecArgs(List<String> execArgs) {
        this.execArgs = execArgs != null ? execArgs : new ArrayList<>();
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : new ArrayList<>();
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
        // Relative paths are resolved against the current working directory and
        // stored as absolute, so build artifacts always land in the project's
        // own target/ directory even when the JVM CWD changes (e.g. Surefire
        // sets CWD to target/ during test runs). Absolute values are kept as-is.
        if (outputDirectory != null && !outputDirectory.trim().isEmpty()
                && !new java.io.File(outputDirectory).isAbsolute()) {
            this.outputDirectory = new java.io.File(outputDirectory).getAbsolutePath();
        } else {
            this.outputDirectory = outputDirectory;
        }
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
        private boolean debug = true;
        
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
        
        public boolean isDebug() {
            return debug;
        }
        
        public void setDebug(boolean debug) {
            this.debug = debug;
        }
    }
}