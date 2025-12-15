package io.github.yasmramos.forge.model;

/**
 * Compilation result containing success status and file counts
 */
public class CompilationResult {
    private final boolean success;
    private final int totalFiles;
    private final int compiledFiles;
    
    public CompilationResult(boolean success, int totalFiles, int compiledFiles) {
        this.success = success;
        this.totalFiles = totalFiles;
        this.compiledFiles = compiledFiles;
    }
    
    public boolean isSuccess() { return success; }
    public int getTotalFiles() { return totalFiles; }
    public int getCompiledFiles() { return compiledFiles; }
    
    public double getSuccessRate() {
        return totalFiles > 0 ? (double) compiledFiles / totalFiles : 0.0;
    }
}