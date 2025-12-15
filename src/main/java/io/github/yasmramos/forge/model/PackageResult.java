package io.github.yasmramos.forge.model;

/**
 * Package result containing success status, type and artifact count
 */
public class PackageResult {
    private final boolean success;
    private final String type;
    private final int artifacts;
    
    public PackageResult(boolean success, String type, int artifacts) {
        this.success = success;
        this.type = type;
        this.artifacts = artifacts;
    }
    
    public boolean isSuccess() { return success; }
    public String getType() { return type; }
    public int getArtifacts() { return artifacts; }
}