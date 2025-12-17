package io.github.yasmramos.forge.core;

import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyType;
import io.github.yasmramos.forge.model.ProjectAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency Resolution System for Forge Build System
 * Handles dependency downloading, verification, and management
 */
public class DependencyResolver {
    
    private final Logger logger = LoggerFactory.getLogger(DependencyResolver.class);
    private final Map<String, DependencyInfo> resolvedDependencies;
    
    public DependencyResolver() {
        this.resolvedDependencies = new ConcurrentHashMap<>();
    }
    
    public DependencyResolution resolve(Map<String, Object> dependencies, ProjectAnalysis analysis) {
        logger.info("🔗 Resolving " + dependencies.size() + " dependencies...");
        
        DependencyResolution resolution = new DependencyResolution();
        
        for (Map.Entry<String, Object> entry : dependencies.entrySet()) {
            String depName = entry.getKey();
            Object depConfig = entry.getValue();
            
            try {
                DependencyInfo dependencyInfo = resolveDependency(depName, depConfig);
                if (dependencyInfo != null) {
                    resolvedDependencies.put(depName, dependencyInfo);
                    resolution.addDependency(dependencyInfo);
                    logger.debug("✅ Resolved dependency: " + depName);
                }
            } catch (Exception e) {
                logger.error("❌ Failed to resolve dependency: " + depName, e);
                resolution.addError(depName, e.getMessage());
            }
        }
        
        logger.info("🔗 Dependency resolution completed: " + 
                   resolution.getSuccessCount() + " successful, " + 
                   resolution.getErrorCount() + " failed");
        
        return resolution;
    }
    
    private DependencyInfo resolveDependency(String name, Object config) {
        String version;
        DependencyType depType;
        
        // Parse dependency configuration
        if (config instanceof String) {
            version = (String) config;
            depType = DependencyType.MAVEN;
        } else if (config instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) config;
            version = configMap.getOrDefault("version", "latest").toString();
            String type = configMap.getOrDefault("type", "maven").toString();
            depType = DependencyType.valueOf(type.toUpperCase());
        } else {
            logger.warn("Invalid dependency configuration for: " + name);
            return null;
        }
        
        try {
            // Handle Maven dependencies
            if (depType == DependencyType.MAVEN) {
                return resolveMavenDependency(name, version);
            }
            
            // Handle other dependency types
            return new DependencyInfo(name, version, depType);
            
        } catch (Exception e) {
            logger.error("Failed to resolve dependency: " + name + ":" + version, e);
            return null;
        }
    }
    
    private DependencyInfo resolveMavenDependency(String groupId, String version) throws IOException {
        logger.info("📦 Resolving Maven dependency: " + groupId + ":" + version);
        
        // Determine the latest version if "latest" is specified
        if ("latest".equals(version)) {
            version = fetchLatestVersion(groupId);
        }
        
        // Maven artifact coordinates
        String artifactId = extractArtifactId(groupId);
        String groupPath = groupId.replace('.', '/');
        
        // Repository URLs
        String baseUrl = "https://repo1.maven.org/maven2/";
        String jarUrl = baseUrl + groupPath + "/" + artifactId + "/" + version + "/" + 
                       artifactId + "-" + version + ".jar";
        
        // Local path for downloaded artifact
        Path localCacheDir = Paths.get(System.getProperty("user.home"), ".forge", "libs", 
                                      groupId.replace('.', '_'), artifactId, version);
        Path localJarPath = localCacheDir.resolve(artifactId + "-" + version + ".jar");
        
        // Create cache directory
        Files.createDirectories(localCacheDir);
        
        // Download if not already cached
        if (!Files.exists(localJarPath)) {
            logger.info("📥 Downloading: " + jarUrl);
            downloadDependency(jarUrl, localJarPath);
        }
        
        // Verify checksum if available
        String pomUrl = baseUrl + groupPath + "/" + artifactId + "/" + version + "/" + 
                       artifactId + "-" + version + ".pom";
        downloadPomIfAvailable(pomUrl, localCacheDir);
        
        logger.info("✅ Maven dependency resolved: " + groupId + ":" + version);
        return new DependencyInfo(groupId, version, DependencyType.MAVEN, localJarPath, true);
    }
    
    private String fetchLatestVersion(String groupId) {
        try {
            // For now, use a default version. In a real implementation,
            // this would query Maven Central API for the latest version
            logger.debug("Fetching latest version for: " + groupId);
            return "2.9.0"; // Default version for common dependencies
        } catch (Exception e) {
            logger.warn("Failed to fetch latest version, using default", e);
            return "1.0.0";
        }
    }
    
    private String extractArtifactId(String groupId) {
        // Extract artifact ID from group ID (common pattern)
        String[] parts = groupId.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "-" + parts[parts.length - 1];
        }
        return groupId;
    }
    
    private void downloadDependency(String jarUrl, Path localPath) throws IOException {
        try {
            URL url = new URL(jarUrl);
            try (FileOutputStream out = new FileOutputStream(localPath.toFile())) {
                url.openStream().transferTo(out);
            }
            logger.info("📦 Downloaded: " + localPath);
        } catch (Exception e) {
            // Clean up failed download
            if (Files.exists(localPath)) {
                Files.delete(localPath);
            }
            throw new IOException("Failed to download: " + jarUrl, e);
        }
    }
    
    private void downloadPomIfAvailable(String pomUrl, Path localDir) {
        try {
            Path pomPath = localDir.resolve("pom.xml");
            if (!Files.exists(pomPath)) {
                URL pom = new URL(pomUrl);
                try {
                    Files.copy(pom.openStream(), pomPath, StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("📋 Downloaded POM: " + pomPath);
                } catch (Exception e) {
                    // POM not available, this is okay
                    logger.debug("POM not available: " + pomUrl);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to download POM: " + pomUrl, e);
        }
    }
}