package io.github.yasmramos.forge.core.packageinfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes deterministic JAR archives (AGENT.md decision 17).
 *
 * <p>Reproducibility is a property of the artifact, not the process: two runs
 * with identical inputs must produce byte-identical bytes. The external
 * {@code jar} tool stamps entry timestamps from the filesystem and orders
 * entries non-deterministically, so packaging goes through this writer
 * instead. Concretely it:</p>
 *
 * <ul>
 *   <li>sets a fixed timestamp on every entry (the resolved build epoch);</li>
 *   <li>sorts entries lexicographically regardless of filesystem order;</li>
 *   <li>writes {@code META-INF/MANIFEST.MF} first with a trailing blank line,
 *       as the JAR spec requires;</li>
 *   <li>stores entries uncompressed, so bytes do not depend on the deflater
 *       implementation or level of a given JDK.</li>
 * </ul>
 */
public final class JarWriter {

    private JarWriter() {
    }

    /**
     * Packages every regular file under {@code root} into {@code jarFile},
     * storing paths relative to {@code root}. An empty root still produces a
     * valid JAR containing only the manifest.
     *
     * @param epochSeconds fixed timestamp for all entries (see
     *                     {@link ReproducibleBuild#epochSeconds()})
     */
    public static void writeDirectory(Path root, Path jarFile, long epochSeconds)
            throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.sort(Comparator.comparing(p -> relativeName(root, p)));

        Files.createDirectories(jarFile.toAbsolutePath().getParent());
        try (OutputStream fos = Files.newOutputStream(jarFile);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            writeManifest(zip, epochSeconds);
            for (Path file : files) {
                String name = relativeName(root, file);
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    continue; // the normalized manifest is already written
                }
                writeEntry(zip, name, Files.readAllBytes(file), epochSeconds);
            }
        }
    }

    /** Writes the given {@code name -> content} entries in sorted order. */
    public static void writeEntries(Path jarFile, List<Entry> entries, long epochSeconds)
            throws IOException {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::getName));

        Files.createDirectories(jarFile.toAbsolutePath().getParent());
        try (OutputStream fos = Files.newOutputStream(jarFile);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            writeManifest(zip, epochSeconds);
            for (Entry e : sorted) {
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(e.getName())) {
                    continue;
                }
                writeEntry(zip, e.getName(), e.getContent(), epochSeconds);
            }
        }
    }

    /** A single JAR entry: a name and its bytes. */
    public static final class Entry {
        private final String name;
        private final byte[] content;

        public Entry(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }

        public String getName() { return name; }
        public byte[] getContent() { return content; }
    }

    private static void writeManifest(ZipOutputStream zip, long epochSeconds)
            throws IOException {
        String manifest = "Manifest-Version: 1.0\r\nCreated-By: Forge\r\n\r\n";
        writeEntry(zip, "META-INF/MANIFEST.MF",
            manifest.getBytes(StandardCharsets.UTF_8), epochSeconds);
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content,
                                   long epochSeconds) throws IOException {
        // STORED with an explicit CRC/size keeps the bytes independent of the
        // deflater implementation and level across JDKs and machines.
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        entry.setTime(epochSeconds * 1000L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /** In-memory helper used by tests to compare two archives byte-for-byte. */
    static byte[] toBytes(List<Entry> entries, long epochSeconds) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            writeManifest(zip, epochSeconds);
            List<Entry> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(Entry::getName));
            for (Entry e : sorted) {
                writeEntry(zip, e.getName(), e.getContent(), epochSeconds);
            }
        }
        return bos.toByteArray();
    }
}
