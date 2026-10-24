package io.github.yasmramos.forge.core.cache;

import io.github.yasmramos.forge.core.task.ForgeTask;
import io.github.yasmramos.forge.core.task.TaskOutput;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Local task build cache under .forge/cache of the project (AGENT.md decision 3).
 * Layout: &lt;task-hash&gt;/outputs.tar.gz. Entries are keyed purely by content
 * hash, so invalidation is never time-based and cached outputs stay valid across
 * git checkouts, machines and CI runners. Archives use gzip only (no mtime), so
 * identical inputs always produce byte-identical entries.
 */
public class TaskCache {

    private static final String ENTRY_FILE = "outputs.tar.gz";

    private final File cacheDir;

    /** Caches under &lt;projectRoot&gt;/.forge/cache by default. */
    public TaskCache(File baseDir) {
        String name = baseDir.getName();
        this.cacheDir = ("cache".equals(name))
            ? baseDir
            : new File(baseDir, ".forge" + File.separator + "cache");
    }

    private File entryFile(String hash) {
        return new File(new File(cacheDir, hash), ENTRY_FILE);
    }

    /** Cache hit iff an entry exists for exactly this content hash. */
    public boolean isUpToDate(String hash, ForgeTask task) {
        return entryFile(hash).isFile();
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
    }

    /** Restores cached outputs into their original relative locations. */
    public void restore(String hash, ForgeTask task, File projectRoot) throws IOException {
        try (InputStream fis = Files.newInputStream(entryFile(hash).toPath());
             TarArchiveReader tar = new TarArchiveReader(new GZIPInputStream(fis))) {
            TarArchiveReader.Entry e;
            while ((e = tar.nextEntry()) != null) {
                File target = new File(projectRoot, e.name);
                Files.createDirectories(target.getParentFile().toPath());
                Files.write(target.toPath(), e.content);
            }
        }
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
