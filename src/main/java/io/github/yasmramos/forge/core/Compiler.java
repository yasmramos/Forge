package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.CompilationResult;
import io.github.yasmramos.forge.cache.ForgeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-Performance Compiler for Forge Build System
 * Supports incremental compilation and parallel processing
 */
public class Compiler {
    
    private final Logger logger = LoggerFactory.getLogger(Compiler.class);
    private final ExecutorService compilerExecutor;
    
    public Compiler() {
        this.compilerExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
    }
    
    public CompilationResult compile(Path sourceFile, DependencyResolution dependencyResolution, ForgeCache cache) {
        String cacheKey = ForgeCache.generateKey(sourceFile.toFile(), 
            dependencyResolution.getDependencies().isEmpty() ? null : 
            createDependencyMap(dependencyResolution));
        
        // Check cache first
        if (cache.isValid(cacheKey, sourceFile.toFile())) {
            logger.debug("📦 Using cached compilation for: " + sourceFile);
            return new CompilationResult(true, 1, 1);
        }
        
        try {
            logger.debug("🔨 Compiling: " + sourceFile);
            
            // Perform actual compilation
            ProcessResult result = executeJavac(sourceFile, dependencyResolution);
            
            if (result.isSuccess()) {
                // Cache successful compilation
                ForgeCache.CacheEntry cacheEntry = new ForgeCache.CacheEntry(
                    sourceFile.toFile().lastModified(), 
                    result.getOutput()
                );
                cache.put(cacheKey, cacheEntry);
                
                return new CompilationResult(true, 1, 1);
            } else {
                logger.error("❌ Compilation failed for: " + sourceFile);
                logger.error("Error: " + result.getError());
                return new CompilationResult(false, 1, 0);
            }
            
        } catch (Exception e) {
            logger.error("❌ Exception during compilation: " + sourceFile, e);
            return new CompilationResult(false, 1, 0);
        }
    }
    
    public CompilationResult compileIncremental(List<Path> changedSources, ForgeCache cache) {
        logger.info("🔄 Incremental compilation of " + changedSources.size() + " changed files");
        
        CompletableFuture<CompilationResult>[] tasks = changedSources.stream()
            .map(sourceFile -> CompletableFuture.supplyAsync(() -> {
                try {
                    return compile(sourceFile, null, cache);
                } catch (Exception e) {
                    logger.error("Failed incremental compile: " + sourceFile, e);
                    return new CompilationResult(false, 1, 0);
                }
            }, compilerExecutor))
            .toArray(CompletableFuture[]::new);
        
        CompletableFuture.allOf(tasks).join();
        
        int totalFiles = changedSources.size();
        int successFiles = 0;
        
        for (CompletableFuture<CompilationResult> task : tasks) {
            try {
                CompilationResult result = task.get();
                if (result.isSuccess()) {
                    successFiles++;
                }
            } catch (Exception e) {
                logger.error("Failed to get compilation result", e);
            }
        }
        
        return new CompilationResult(successFiles == totalFiles, totalFiles, successFiles);
    }
    
    private ProcessResult executeJavac(Path sourceFile, DependencyResolution dependencyResolution) {
        try {
            List<String> command = new ArrayList<>();
            command.add("javac");
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-d");
            command.add("target/classes");
            
            // Add classpath
            String classpath = buildClasspath(dependencyResolution);
            if (!classpath.isEmpty()) {
                command.add("-cp");
                command.add(classpath);
            }
            
            command.add(sourceFile.toString());
            
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            
            // Read output and error streams
            byte[] outputBytes = process.getInputStream().readAllBytes();
            byte[] errorBytes = process.getErrorStream().readAllBytes();
            
            int exitCode = process.waitFor();
            
            return new ProcessResult(exitCode == 0, outputBytes, errorBytes);
            
        } catch (IOException | InterruptedException e) {
            return new ProcessResult(false, new byte[0], e.getMessage().getBytes());
        }
    }
    
    private String buildClasspath(DependencyResolution dependencyResolution) {
        if (dependencyResolution == null || dependencyResolution.getDependencies().isEmpty()) {
            return "";
        }
        
        StringBuilder classpath = new StringBuilder();
        boolean first = true;
        
        for (io.github.yasmramos.forge.model.DependencyInfo dep : dependencyResolution.getDependencies()) {
            if (!first) {
                classpath.append(File.pathSeparator);
            }
            first = false;
            
            if (dep.isResolved() && dep.getLocalFile().exists()) {
                classpath.append(dep.getLocalFile().getAbsolutePath());
            }
        }
        
        return classpath.toString();
    }
    
    private Map<String, Object> createDependencyMap(DependencyResolution dependencyResolution) {
        // Create a simple map representation for cache key generation
        java.util.Map<String, Object> depMap = new java.util.HashMap<>();
        for (io.github.yasmramos.forge.model.DependencyInfo dep : dependencyResolution.getDependencies()) {
            depMap.put(dep.getName(), dep.getVersion());
        }
        return depMap;
    }
    
    public void shutdown() {
        compilerExecutor.shutdown();
    }
    
    private static class ProcessResult {
        private final boolean success;
        private final byte[] output;
        private final byte[] error;
        
        public ProcessResult(boolean success, byte[] output, byte[] error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }
        
        public boolean isSuccess() { return success; }
        public byte[] getOutput() { return output; }
        public byte[] getError() { return error; }
    }
}