package io.github.yasmramos.forge.core.processor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers annotation processors declared by the compile classpath
 * (AGENT.md section 23).
 *
 * <p>Standard Java service discovery: a jar or directory that contains
 * {@code META-INF/services/javax.annotation.processing.Processor} declares the
 * processors it provides. Declaring a dependency on Lombok already declares the
 * intent to run its processor, so discovering it inside the jar honors that
 * declaration rather than adding magic — unlike plugins, which change build
 * configuration and must stay explicit (section 4).</p>
 *
 * <p>The processor path is kept separate from the compile classpath, as
 * required for Lombok, MapStruct and Dagger.</p>
 */
public final class AnnotationProcessors {

    /** Service file every {@code javax.annotation.processing.Processor} provider registers in. */
    public static final String SERVICE_FILE = "META-INF/services/javax.annotation.processing.Processor";

    private AnnotationProcessors() {
    }

    /** Discovered processors and the entries that provide them. */
    public static final class Discovery {
        private final List<String> names;
        private final List<File> processorPath;

        Discovery(List<String> names, List<File> processorPath) {
            this.names = Collections.unmodifiableList(names);
            this.processorPath = Collections.unmodifiableList(processorPath);
        }

        /** Fully-qualified processor class names, sorted and de-duplicated. */
        public List<String> getNames() {
            return names;
        }

        /** Classpath entries that carry at least one processor, for {@code -processorpath}. */
        public List<File> getProcessorPath() {
            return processorPath;
        }

        public boolean isEmpty() {
            return names.isEmpty();
        }
    }

    /**
     * Scan {@code classpathEntries} for declared processors. Directories and
     * jars are both understood; unreadable or non-existent entries are skipped
     * (a missing jar is reported later, by resolution, not here).
     */
    public static Discovery discover(List<File> classpathEntries) {
        Set<String> names = new LinkedHashSet<>();
        List<File> processorPath = new ArrayList<>();
        if (classpathEntries == null) {
            return new Discovery(new ArrayList<>(), new ArrayList<>());
        }
        for (File entry : classpathEntries) {
            List<String> found = readServiceFile(entry);
            if (!found.isEmpty()) {
                names.addAll(found);
                processorPath.add(entry);
            }
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return new Discovery(sorted, processorPath);
    }

    /** Processor class names declared by a single classpath entry, in file order. */
    public static List<String> readServiceFile(File entry) {
        if (entry == null || !entry.exists()) {
            return Collections.emptyList();
        }
        if (entry.isDirectory()) {
            Path service = entry.toPath().resolve(SERVICE_FILE);
            if (!Files.isRegularFile(service)) {
                return Collections.emptyList();
            }
            try {
                return parse(Files.newInputStream(service));
            } catch (IOException e) {
                return Collections.emptyList();
            }
        }
        if (!entry.getName().endsWith(".jar")) {
            return Collections.emptyList();
        }
        try (ZipFile zip = new ZipFile(entry)) {
            ZipEntry service = zip.getEntry(SERVICE_FILE);
            if (service == null) {
                return Collections.emptyList();
            }
            try (InputStream in = zip.getInputStream(service)) {
                return parse(in);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** Reads one class name per line, ignoring blanks and {@code #} comments. */
    private static List<String> parse(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                names.add(name);
            }
        }
        return names;
    }
}
