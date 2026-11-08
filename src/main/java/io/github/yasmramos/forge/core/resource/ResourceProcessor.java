package io.github.yasmramos.forge.core.resource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Copies resource directories into the compiled-classes directory
 * (AGENT.md section 22), so the packaged jar actually contains
 * {@code application.properties}, {@code META-INF/services/*}, log
 * configuration and i18n bundles instead of only {@code .class} files.
 *
 * <p>Two rules from the ADR shape the behavior:</p>
 * <ul>
 *   <li><b>Filtering is opt-in</b> (section 22). By default a resource is copied
 *   byte-for-byte; {@code ${...}} substitution runs only when the project asks
 *   for it, because a binary file that happens to contain {@code ${something}}
 *   must not be corrupted.</li>
 *   <li><b>Copy only if changed, by content</b> (section 3): an unchanged
 *   resource is left untouched, never rewritten based on timestamps.</li>
 * </ul>
 */
public final class ResourceProcessor {

    private ResourceProcessor() {
    }

    /** Outcome of a resource copy: how many files were written, skipped or total. */
    public static final class Result {
        private final int copied;
        private final int unchanged;
        private final int total;

        Result(int copied, int unchanged, int total) {
            this.copied = copied;
            this.unchanged = unchanged;
            this.total = total;
        }

        public int getCopied() { return copied; }
        public int getUnchanged() { return unchanged; }
        public int getTotal() { return total; }
    }

    /**
     * Copies every file under {@code resourceRoots} into {@code outputDir},
     * preserving the directory layout relative to each root.
     *
     * @param resourceRoots directories holding resources; missing ones are skipped
     * @param outputDir     destination (the compiled-classes directory)
     * @param filtering     when true, expands {@code ${key}} using {@code properties}
     * @param properties    property values for filtering; may be null
     */
    public static Result copy(List<File> resourceRoots, Path outputDir, boolean filtering,
                              Map<String, String> properties) throws IOException {
        int copied = 0;
        int unchanged = 0;
        int total = 0;

        for (File root : resourceRoots) {
            if (root == null || !root.isDirectory()) {
                continue;
            }
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            // Sorted so the operation is deterministic regardless of filesystem order.
            files.sort(Comparator.comparing(Path::toString));

            for (Path file : files) {
                total++;
                String relative = rootPath.relativize(file).toString();
                Path target = outputDir.resolve(relative);
                byte[] content = Files.readAllBytes(file);
                if (filtering) {
                    content = filter(content, properties);
                }
                if (Files.isRegularFile(target)
                        && java.util.Arrays.equals(Files.readAllBytes(target), content)) {
                    unchanged++;
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(target, content);
                copied++;
            }
        }
        return new Result(copied, unchanged, total);
    }

    /** Expands {@code ${key}} placeholders against the given properties. */
    static byte[] filter(byte[] content, Map<String, String> properties) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (properties != null) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                text = text.replace("${" + e.getKey() + "}", e.getValue());
            }
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
