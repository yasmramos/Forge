package io.github.yasmramos.forge.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intelligent Cache System for Forge Build System
 * Provides fast incremental builds and dependency caching
 */
public class ForgeCache {
    
    private final Logger logger = LoggerFactory.getLogger(ForgeCache.class);
    private final Map<String, CacheEntry> cache;
    private final Path cacheDirectory;
    
    public ForgeCache() {
        this.cache = new ConcurrentHashMap<>();
        this.cacheDirectory = Paths.get(System.getProperty("user.home"), ".forge", "cache");
        
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            logger.warn("Failed to create cache directory", e);
        }
    }
    
    public void put(String key, CacheEntry entry) {
        cache.put(key, entry);
        persistToDisk(key, entry);
    }
    
    public CacheEntry get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            entry = loadFromDisk(key);
            if (entry != null) {
                cache.put(key, entry);
            }
        }
        return entry;
    }
    
    public boolean isValid(String key, File sourceFile) {
        CacheEntry entry = get(key);
        if (entry == null) {
            return false;
        }
        
        long currentLastModified = sourceFile.lastModified();
        return currentLastModified == entry.getLastModified();
    }
    
    public void clear() {
        cache.clear();
        try {
            if (Files.exists(cacheDirectory)) {
                Files.walk(cacheDirectory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            logger.warn("Failed to delete cache file: " + path, e);
                        }
                    });
            }
        } catch (IOException e) {
            logger.warn("Failed to clear cache directory", e);
        }
    }
    
    private void persistToDisk(String key, CacheEntry entry) {
        try {
            Path cacheFile = cacheDirectory.resolve(key + ".cache");
            // TODO: Implement serialization to disk
        } catch (Exception e) {
            logger.debug("Failed to persist cache entry to disk", e);
        }
    }
    
    private CacheEntry loadFromDisk(String key) {
        try {
            Path cacheFile = cacheDirectory.resolve(key + ".cache");
            if (Files.exists(cacheFile)) {
                // TODO: Implement deserialization from disk
                return null;
            }
        } catch (Exception e) {
            logger.debug("Failed to load cache entry from disk", e);
        }
        return null;
    }
    
    public static String generateKey(File sourceFile, Map<String, Object> dependencies) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            
            // Include file path and content hash
            String fileInfo = sourceFile.getAbsolutePath() + ":" + sourceFile.lastModified();
            md.update(fileInfo.getBytes());
            
            // Include dependency information
            if (dependencies != null) {
                String depInfo = dependencies.toString();
                md.update(depInfo.getBytes());
            }
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
        } catch (Exception e) {
            // Fallback to simple key
            return sourceFile.getAbsolutePath().hashCode() + ":" + sourceFile.lastModified();
        }
    }
    
    public static class CacheEntry {
        private final long lastModified;
        private final byte[] compiledData;
        private final Map<String, String> metadata;
        
        public CacheEntry(long lastModified, byte[] compiledData) {
            this.lastModified = lastModified;
            this.compiledData = compiledData;
            this.metadata = new ConcurrentHashMap<>();
        }
        
        public CacheEntry(long lastModified, byte[] compiledData, Map<String, String> metadata) {
            this.lastModified = lastModified;
            this.compiledData = compiledData;
            this.metadata = metadata;
        }
        
        public long getLastModified() {
            return lastModified;
        }
        
        public byte[] getCompiledData() {
            return compiledData;
        }
        
        public Map<String, String> getMetadata() {
            return metadata;
        }
        
        public void putMetadata(String key, String value) {
            metadata.put(key, value);
        }
        
        public String getMetadata(String key) {
            return metadata.get(key);
        }
    }
}