package io.github.yasmramos.forge.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency resolution result containing resolved dependencies and errors
 */
public class DependencyResolution {
    private final List<DependencyInfo> dependencies;
    private final Map<String, String> errors;
    
    public DependencyResolution() {
        this.dependencies = new ArrayList<>();
        this.errors = new ConcurrentHashMap<>();
    }
    
    public void addDependency(DependencyInfo dependency) {
        dependencies.add(dependency);
    }
    
    public void addError(String dependency, String error) {
        errors.put(dependency, error);
    }
    
    public List<DependencyInfo> getDependencies() {
        return dependencies;
    }
    
    public Map<String, String> getErrors() {
        return errors;
    }
    
    public int getSuccessCount() {
        return dependencies.size();
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}