package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Shared source discovery for plugin extension contributors (AGENT.md section
 * 4). Both the compiler contributor and the test-runner contributor claim
 * sources by extension under the project's source roots, so the discovery rules
 * live in one place.
 */
final class PluginSourceScanner {

    private PluginSourceScanner() {
    }

    /**
     * Sources with {@code extension} under {@code directories}, sorted and
     * de-duplicated. A directory that cannot be scanned fails the build with
     * {@code errorCode} rather than silently compiling fewer files than exist.
     */
    static List<File> discover(List<Path> directories, String extension, ErrorCode.Entry errorCode) {
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        Set<File> found = new LinkedHashSet<>();
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .forEach(p -> found.add(p.toFile()));
            } catch (java.io.IOException e) {
                throw new ForgeException(errorCode,
                    "cannot scan source directory " + dir + " for '" + extension
                        + "' sources: " + e.getMessage(), e);
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * Directories a plugin language's sources are looked up under: the
     * configured source directory and source paths, resolved against the
     * project root, defaulting to {@code src/main/java}.
     */
    static List<Path> mainSourceDirectories(ProjectConfig config, File projectRoot) {
        Set<Path> dirs = new LinkedHashSet<>();
        String configured = config.getSourceDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            dirs.add(resolveAgainstRoot(projectRoot, configured));
        }
        if (config.getSourcePaths() != null) {
            for (String p : config.getSourcePaths()) {
                if (p != null && !p.trim().isEmpty()) {
                    dirs.add(resolveAgainstRoot(projectRoot, p));
                }
            }
        }
        if (dirs.isEmpty()) {
            dirs.add(new File(projectRoot, "src/main/java").toPath());
        }
        return new ArrayList<>(dirs);
    }

    /**
     * Directories a plugin language's tests are looked up under. Test sources
     * conventionally live beside the main ones, so the main source roots are
     * included; a project may point elsewhere through {@code sourcePaths}.
     */
    static List<Path> testSourceDirectories(ProjectConfig config, File projectRoot) {
        Set<Path> dirs = new LinkedHashSet<>();
        for (String candidate : new String[]{"src/test/java", "test", "src/test"}) {
            dirs.add(new File(projectRoot, candidate).toPath());
        }
        dirs.addAll(mainSourceDirectories(config, projectRoot));
        return new ArrayList<>(dirs);
    }

    private static Path resolveAgainstRoot(File projectRoot, String path) {
        Path p = new File(path).toPath();
        return p.isAbsolute() ? p : new File(projectRoot, path).toPath();
    }
}
