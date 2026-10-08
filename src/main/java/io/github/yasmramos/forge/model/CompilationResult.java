package io.github.yasmramos.forge.model;

/**
 * Compilation result containing success status and file counts
 */
public class CompilationResult {
    private final boolean success;
    private final int totalFiles;
    private final int compiledFiles;
    private final int cachedFiles;

    public CompilationResult(boolean success, int totalFiles, int compiledFiles) {
        this(success, totalFiles, compiledFiles, 0);
    }

    public CompilationResult(boolean success, int totalFiles, int compiledFiles, int cachedFiles) {
        this.success = success;
        this.totalFiles = totalFiles;
        this.compiledFiles = compiledFiles;
        this.cachedFiles = cachedFiles;
    }
    
    public boolean isSuccess() { return success; }
    public int getTotalFiles() { return totalFiles; }
    public int getCompiledFiles() { return compiledFiles; }
    /** Number of files served from the build cache instead of being recompiled. */
    public int getCachedFiles() { return cachedFiles; }
    
    public double getSuccessRate() {
        return totalFiles > 0 ? (double) compiledFiles / totalFiles : 0.0;
    }
}