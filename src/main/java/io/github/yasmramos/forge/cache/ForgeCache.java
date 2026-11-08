package io.github.yasmramos.forge.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
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
        this(Paths.get(System.getProperty("user.home"), ".forge", "cache").toString());
    }

    /**
     * Create a cache backed by the given directory on disk.
     *
     * @param cacheDirectory path where cache entries are persisted
     */
    public ForgeCache(String cacheDirectory) {
        this.cache = new ConcurrentHashMap<>();
        this.cacheDirectory = Paths.get(cacheDirectory);
        
        try {
            Files.createDirectories(this.cacheDirectory);
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
    
    /**
     * Validity is decided by <em>content</em>, never by timestamps (AGENT.md
     * section 3). The key already encodes a SHA-256 of the source bytes, so a
     * present entry means "these exact bytes compiled"; comparing
     * {@code lastModified} here would only reintroduce clock dependence and
     * turn a re-checkout or an {@code rsync -a} (same bytes, new mtime) into a
     * spurious cache miss.
     */
    public boolean isValid(String key, File sourceFile) {
        return get(key) != null;
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

            // Serialize the entry: lastModified (8 bytes) + compiled data length (4 bytes)
            // + compiled data + metadata as UTF-8 "key=value" lines prefixed by its byte length
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeLong(entry.getLastModified());
            byte[] data = entry.getCompiledData() != null ? entry.getCompiledData() : new byte[0];
            out.writeInt(data.length);
            out.write(data);

            Properties props = new Properties();
            for (Map.Entry<String, String> meta : entry.getMetadata().entrySet()) {
                props.setProperty(meta.getKey(), meta.getValue());
            }
            ByteArrayOutputStream metaBuffer = new ByteArrayOutputStream();
            props.store(metaBuffer, null);
            byte[] metaBytes = metaBuffer.toByteArray();
            out.writeInt(metaBytes.length);
            out.write(metaBytes);

            // Stored outputs: each class file the compilation produced, as
            // relative path + bytes. Section 3 requires outputs to be stored and
            // put back in position on a hit; without this a hit only proved a
            // file with the right name existed, which served a stale class after
            // the same key was reached again by a different edit (e.g. a
            // constant toggled back and forth).
            out.writeInt(entry.getOutputs().size());
            for (Map.Entry<String, byte[]> output : new TreeMap<>(entry.getOutputs()).entrySet()) {
                byte[] pathBytes = output.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                byte[] content = output.getValue() != null ? output.getValue() : new byte[0];
                out.writeInt(content.length);
                out.write(content);
            }
            out.flush();

            Files.write(cacheFile, buffer.toByteArray());
            logger.debug("Persisted cache entry: " + key);
        } catch (Exception e) {
            logger.debug("Failed to persist cache entry to disk", e);
        }
    }

    private CacheEntry loadFromDisk(String key) {
        try {
            Path cacheFile = cacheDirectory.resolve(key + ".cache");
            if (Files.exists(cacheFile)) {
                byte[] raw = Files.readAllBytes(cacheFile);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));

                long lastModified = in.readLong();
                int dataLen = in.readInt();
                byte[] data = new byte[dataLen];
                in.readFully(data);

                int metaLen = in.readInt();
                byte[] metaBytes = new byte[metaLen];
                in.readFully(metaBytes);

                Properties props = new Properties();
                props.load(new ByteArrayInputStream(metaBytes));

                Map<String, String> metadata = new ConcurrentHashMap<>();
                for (String name : props.stringPropertyNames()) {
                    metadata.put(name, props.getProperty(name));
                }

                CacheEntry entry = new CacheEntry(lastModified, data, metadata);

                // Entries written before outputs were stored simply end here.
                if (in.available() > 0) {
                    int outputCount = in.readInt();
                    for (int i = 0; i < outputCount; i++) {
                        int pathLen = in.readInt();
                        byte[] pathBytes = new byte[pathLen];
                        in.readFully(pathBytes);
                        int contentLen = in.readInt();
                        byte[] content = new byte[contentLen];
                        in.readFully(content);
                        entry.putOutput(new String(pathBytes, StandardCharsets.UTF_8), content);
                    }
                }

                logger.debug("Loaded cache entry from disk: " + key);
                return entry;
            }
        } catch (Exception e) {
            logger.debug("Failed to load cache entry from disk", e);
        }
        return null;
    }
    
    /**
     * Content-addressed cache key for a source file.
     *
     * <p>Identity comes from the file's <em>content</em> and its position
     * relative to the project root (never an absolute path), plus a
     * deterministic rendering of the dependency view. Two checkouts of the same
     * project on different machines therefore yield the same key (AGENT.md
     * section 17), and a dependency set yields the same key regardless of map
     * iteration order.</p>
     */
    public static String generateKey(File sourceFile, Map<String, Object> dependencies) {
        return generateKey(sourceFile, dependencies, null);
    }

    /**
     * As {@link #generateKey(File, Map)} but scoped to a project identity.
     *
     * <p>The default cache lives in {@code ~/.forge/cache} and is shared by
     * every project on the machine, so the project identity (its stable
     * {@code group:name} coordinates) is mixed in to stop two unrelated
     * projects with a same-named file from sharing an entry. It is stable
     * across machines, unlike an absolute path.</p>
     */
    public static String generateKey(File sourceFile, Map<String, Object> dependencies, String projectId) {
        return generateKey(sourceFile, dependencies, projectId, null);
    }

    /**
     * As {@link #generateKey(File, Map, String)} but with a caller-supplied
     * content hash.
     *
     * <p>Compilation passes the file's <em>effective</em> hash - its own content
     * folded together with its transitive source dependencies - so a change in a
     * dependency cascades into this file's key even when its own bytes are
     * unchanged (AGENT.md sections 3 and 17). When {@code contentHash} is null
     * the file's own bytes are hashed, as before.</p>
     */
    public static String generateKey(File sourceFile, Map<String, Object> dependencies, String projectId,
                                     String contentHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            if (projectId != null && !projectId.isEmpty()) {
                md.update(projectId.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }

            // Content identity (AGENT.md section 3): hash the bytes, not the
            // timestamp. The relative path disambiguates files with identical
            // content while staying portable across machines and checkouts.
            md.update(relativePathOf(sourceFile).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            if (contentHash != null) {
                md.update(contentHash.getBytes(StandardCharsets.UTF_8));
            } else {
                try {
                    md.update(Files.readAllBytes(sourceFile.toPath()));
                } catch (Exception readFailure) {
                    // Unreadable file: fall back to the timestamp so the entry
                    // still changes when the file does, as before.
                    md.update(Long.toString(sourceFile.lastModified()).getBytes(StandardCharsets.UTF_8));
                }
            }

            // Deterministic dependency view: sorted by name so the same set of
            // dependencies yields the same key whatever the map order is.
            if (dependencies != null && !dependencies.isEmpty()) {
                md.update((byte) 0);
                for (String name : new TreeSet<>(dependencies.keySet())) {
                    md.update(name.getBytes(StandardCharsets.UTF_8));
                    md.update((byte) '=');
                    md.update(String.valueOf(dependencies.get(name)).getBytes(StandardCharsets.UTF_8));
                    md.update((byte) '\n');
                }
            }

            return toHex(md.digest());
        } catch (Exception e) {
            // Digest algorithm unavailable: degrade to a name+timestamp key
            // rather than throwing from a cache lookup.
            return sourceFile.getName() + ":" + sourceFile.lastModified();
        }
    }

    /**
     * Path of the file relative to the project root (the nearest ancestor
     * holding a {@code forge.toml}), or to its own directory when no root is
     * found. Relative so the key never depends on where the project lives.
     */
    private static String relativePathOf(File sourceFile) {
        Path absolute = sourceFile.getAbsoluteFile().toPath();
        Path root = findProjectRoot(absolute);
        Path base = root != null ? root : absolute.getParent();
        if (base == null) {
            return sourceFile.getName();
        }
        try {
            return base.relativize(absolute).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return sourceFile.getName();
        }
    }

    private static Path findProjectRoot(Path file) {
        Path dir = file.getParent();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("forge.toml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** Lowercase hex representation of a digest. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class CacheEntry {
        private final long lastModified;
        private final byte[] compiledData;
        private final Map<String, String> metadata;
        private final Map<String, byte[]> outputs;
        
        public CacheEntry(long lastModified, byte[] compiledData) {
            this.lastModified = lastModified;
            this.compiledData = compiledData;
            this.metadata = new ConcurrentHashMap<>();
            this.outputs = new ConcurrentHashMap<>();
        }
        
        public CacheEntry(long lastModified, byte[] compiledData, Map<String, String> metadata) {
            this.lastModified = lastModified;
            this.compiledData = compiledData;
            this.metadata = metadata;
            this.outputs = new ConcurrentHashMap<>();
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

        /** Class files this entry stands for, keyed by path relative to the output dir. */
        public Map<String, byte[]> getOutputs() {
            return outputs;
        }

        public void putOutput(String relativePath, byte[] content) {
            outputs.put(relativePath, content);
        }
    }
}
