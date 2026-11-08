package io.github.yasmramos.forge.core.cache;

import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Local task build cache under .forge/cache of the project (AGENT.md decision 3),
 * optionally backed by a remote cache (section 3). Layout:
 * &lt;task-hash&gt;/outputs.tar.gz. Entries are keyed purely by content hash, so
 * invalidation is never time-based and cached outputs stay valid across git
 * checkouts, machines and CI runners. Archives use gzip only (no mtime), so
 * identical inputs always produce byte-identical entries.
 *
 * <p>With a remote cache configured, a local miss consults the remote and, on a
 * hit, seeds the local entry before the task is considered up to date. Uploads
 * are deferred to {@link #flushRemote()} so they happen at end-of-build (section
 * 16bis): a mid-build crash simply uploads nothing, which is safe because
 * entries are immutable.</p>
 */
public class TaskCache {

    private static final Logger logger = LoggerFactory.getLogger(TaskCache.class);
    private static final String ENTRY_FILE = "outputs.tar.gz";

    private final File cacheDir;
    private final RemoteCache remote;
    /** Hashes written this build, uploaded by {@link #flushRemote()}. */
    private final List<String> pendingUploads =
        Collections.synchronizedList(new ArrayList<>());

    /** Caches under &lt;projectRoot&gt;/.forge/cache by default, local only. */
    public TaskCache(File baseDir) {
        this(baseDir, null);
    }

    /** Caches locally and mirrors misses/uploads through {@code remote}. */
    public TaskCache(File baseDir, RemoteCache remote) {
        String name = baseDir.getName();
        this.cacheDir = ("cache".equals(name))
            ? baseDir
            : new File(baseDir, ".forge" + File.separator + "cache");
        this.remote = remote;
    }

    private File entryFile(String hash) {
        return new File(new File(cacheDir, hash), ENTRY_FILE);
    }

    /**
     * Cache hit iff an entry exists for exactly this content hash. On a local
     * miss with a remote cache configured, the remote is consulted and a hit is
     * seeded into the local cache; a remote failure is a miss, never an error
     * (section 12).
     */
    public boolean isUpToDate(String hash, ForgeTask task) {
        if (entryFile(hash).isFile()) {
            return true;
        }
        if (remote == null) {
            return false;
        }
        Optional<byte[]> fetched = remote.get(hash);
        if (!fetched.isPresent()) {
            return false;
        }
        if (!RemoteCache.isValidArchive(fetched.get())) {
            logger.warn("Remote cache entry {} is not a valid archive ({}); ignoring",
                hash, RemoteCache.describe(fetched.get()));
            return false;
        }
        try {
            writeEntryBytes(hash, fetched.get());
            logger.info("Remote cache hit for {} ({}), seeded locally",
                hash, RemoteCache.describe(fetched.get()));
            return true;
        } catch (IOException e) {
            logger.warn("Could not seed remote cache entry {} locally ({}); "
                + "building locally", hash, e.getMessage());
            return false;
        }
    }

    /** Stores all declared outputs of the task, gzipped, under its hash key. */
    public void store(String hash, ForgeTask task, File projectRoot) throws IOException {
        File target = entryFile(hash);
        Files.createDirectories(target.getParentFile().toPath());
        Path tmp = target.toPath().resolveSibling(ENTRY_FILE + ".tmp");
        try (OutputStream fos = Files.newOutputStream(tmp);
             TarArchiveWriter tar = new TarArchiveWriter(new GZIPOutputStream(fos))) {
            for (TaskOutput out : task.getOutputs()) {
                for (File f : out.resolve(projectRoot)) {
                    if (f.isDirectory()) {
                        List<File> files = new ArrayList<>();
                        collect(f, files);
                        for (File g : files) {
                            tar.write(relativize(projectRoot, g), Files.readAllBytes(g.toPath()));
                        }
                    } else if (f.isFile()) {
                        tar.write(relativize(projectRoot, f), Files.readAllBytes(f.toPath()));
                    }
                }
            }
        }
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (remote != null) {
            pendingUploads.add(hash);
        }
    }

    /** Restores cached outputs into their original relative locations. */
    public void restore(String hash, ForgeTask task, File projectRoot) throws IOException {
        try (InputStream fis = Files.newInputStream(entryFile(hash).toPath());
             TarArchiveReader tar = new TarArchiveReader(new GZIPInputStream(fis))) {
            Path root = projectRoot.toPath().toAbsolutePath().normalize();
            TarArchiveReader.Entry e;
            while ((e = tar.nextEntry()) != null) {
                File target = new File(projectRoot, e.name);
                Path resolved = target.toPath().toAbsolutePath().normalize();
                // Cache content may come from a remote server; never let an
                // entry escape the project root (path traversal).
                if (!resolved.startsWith(root)) {
                    throw new IOException("cache entry escapes project root: " + e.name);
                }
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), e.content);
            }
        }
    }

    private void writeEntryBytes(String hash, byte[] archive) throws IOException {
        File target = entryFile(hash);
        Files.createDirectories(target.getParentFile().toPath());
        Path tmp = target.toPath().resolveSibling(ENTRY_FILE + ".tmp");
        Files.write(tmp, archive);
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Uploads every entry written this build to the remote cache (section
     * 16bis: end-of-build upload). Returns the number of entries uploaded.
     * Never throws: the local cache stays authoritative and a remote outage
     * must not fail a successful build.
     */
    public int flushRemote() {
        if (remote == null) {
            return 0;
        }
        List<String> toUpload;
        synchronized (pendingUploads) {
            toUpload = new ArrayList<>(pendingUploads);
            pendingUploads.clear();
        }
        int uploaded = 0;
        for (String hash : toUpload) {
            File entry = entryFile(hash);
            if (!entry.isFile()) {
                continue;
            }
            try {
                if (remote.put(hash, Files.readAllBytes(entry.toPath()))) {
                    uploaded++;
                }
            } catch (IOException e) {
                logger.warn("Could not read local entry {} for upload ({}); skipped",
                    hash, e.getMessage());
            }
        }
        if (!toUpload.isEmpty()) {
            logger.info("Remote cache: uploaded " + uploaded + "/" + toUpload.size()
                + " entr" + (toUpload.size() == 1 ? "y" : "ies"));
        }
        return uploaded;
    }

    /** True when a remote cache is configured. */
    public boolean hasRemote() {
        return remote != null;
    }

    public void clear() throws IOException {
        if (cacheDir.isDirectory()) {
            deleteRecursively(cacheDir.toPath());
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children); // deterministic order regardless of filesystem
        for (File c : children) {
            if (c.isDirectory()) collect(c, out); else out.add(c);
        }
    }

    private static String relativize(File root, File f) {
        return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var stream = Files.list(p)) {
                for (Path child : stream.toArray(Path[]::new)) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
