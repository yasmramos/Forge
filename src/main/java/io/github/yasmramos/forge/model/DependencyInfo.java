package io.github.yasmramos.forge.model;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dependency information including name, version, type and local path
 */
public class DependencyInfo {
    private final String name;
    private final String version;
    private final DependencyType type;
    private final Path localPath;
    private final boolean resolved;
    
    public DependencyInfo(String name, String version, DependencyType type) {
        this.name = name;
        this.version = version;
        this.type = type;
        this.localPath = Paths.get(System.getProperty("user.home"), ".forge", "libs", name + "-" + version + ".jar");
        this.resolved = true;
    }
    
    public DependencyInfo(String name, String version, DependencyType type, Path localPath, boolean resolved) {
        this.name = name;
        this.version = version;
        this.type = type;
        this.localPath = localPath;
        this.resolved = resolved;
    }
    
    public String getName() { return name; }
    public String getVersion() { return version; }
    public DependencyType getType() { return type; }
    public Path getLocalPath() { return localPath; }
    public boolean isResolved() { return resolved; }
    
    public File getLocalFile() {
        return localPath.toFile();
    }
    
    @Override
    public String toString() {
        return name + ":" + version + " (" + type + ")";
    }
}