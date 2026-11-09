package io.github.yasmramos.forge.core.ide;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.core.resolver.AetherResolution;
import io.github.yasmramos.forge.model.DependencyInfo;
import io.github.yasmramos.forge.model.DependencyResolution;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a {@link ProjectModel} from a resolved {@link ProjectConfig} (AGENT.md
 * section 13, phase 1).
 *
 * <p>Source and resource roots come from the configuration with the same
 * conventional fallbacks the build uses, and are relativised against the project
 * root so the generated files stay portable across checkouts. Libraries come
 * from the same Aether resolution the compiler uses, so the IDE classpath
 * matches the build classpath instead of drifting from it.</p>
 */
public final class IdeProjectBuilder {

    private IdeProjectBuilder() {
    }

    public static ProjectModel build(ProjectConfig config, File projectRoot) {
        String name = config.getName() == null || config.getName().trim().isEmpty()
            ? "project" : config.getName();
        String level = firstNonBlank(config.getSourceCompatibility(),
            config.getTargetCompatibility(), "11");

        List<String> mainSources = relativise(projectRoot, mainSourceRoots(config, projectRoot));
        List<String> testSources = relativise(projectRoot, testSourceRoots(config, projectRoot));
        List<String> mainResources = relativise(projectRoot, mainResourceRoots(config, projectRoot));
        List<String> testResources = relativise(projectRoot, testResourceRoots(config, projectRoot));

        String classesDir = relativisePath(projectRoot, config.getOutputDirectory(), "target/classes");
        String testClassesDir = sibling(classesDir, "test-classes");

        DependencyResolution compile = AetherResolution.resolve(
            config, DependencyScopes.COMPILE, null);
        DependencyResolution test = AetherResolution.resolve(
            config, DependencyScopes.TEST, null);

        // The test classpath is a superset of compile; only the extra libraries
        // belong to the test scope, otherwise every jar would be listed twice.
        Set<String> compilePaths = new LinkedHashSet<>(pathsOf(compile));
        List<String> compileLibs = new ArrayList<>(compilePaths);
        List<String> testLibs = new ArrayList<>();
        for (String path : pathsOf(test)) {
            if (!compilePaths.contains(path)) {
                testLibs.add(path);
            }
        }

        return new ProjectModel(name, level, mainSources, testSources,
            mainResources, testResources, classesDir, testClassesDir,
            compileLibs, testLibs);
    }

    private static List<String> pathsOf(DependencyResolution resolution) {
        List<String> paths = new ArrayList<>();
        for (DependencyInfo info : resolution.getDependencies()) {
            if (info.isResolved() && info.getLocalPath() != null) {
                paths.add(info.getLocalPath().toString());
            }
        }
        return paths;
    }

    private static List<String> mainSourceRoots(ProjectConfig config, File projectRoot) {
        List<String> roots = new ArrayList<>(config.getMainJavaSources());
        if (roots.isEmpty()) {
            String configured = config.getSourceDirectory();
            roots.add(configured != null && !configured.trim().isEmpty()
                ? configured : "src/main/java");
        }
        return roots;
    }

    private static List<String> testSourceRoots(ProjectConfig config, File projectRoot) {
        List<String> roots = new ArrayList<>(config.getTestJavaSources());
        if (roots.isEmpty()) {
            roots.add("src/test/java");
        }
        return roots;
    }

    private static List<String> mainResourceRoots(ProjectConfig config, File projectRoot) {
        List<String> roots = new ArrayList<>(config.getMainResourcePaths());
        if (roots.isEmpty()) {
            roots.add("src/main/resources");
        }
        return roots;
    }

    private static List<String> testResourceRoots(ProjectConfig config, File projectRoot) {
        List<String> roots = new ArrayList<>(config.getTestResourcePaths());
        if (roots.isEmpty()) {
            roots.add("src/test/resources");
        }
        return roots;
    }

    /** Project-relative, forward-slashed paths; absolute inputs are made relative. */
    private static List<String> relativise(File projectRoot, List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String path : paths) {
            out.add(relativisePath(projectRoot, path, path));
        }
        return out;
    }

    private static String relativisePath(File projectRoot, String path, String fallback) {
        if (path == null || path.trim().isEmpty()) {
            return fallback;
        }
        Path candidate = new File(path).toPath();
        if (!candidate.isAbsolute()) {
            return path.replace(File.separatorChar, '/');
        }
        try {
            Path root = projectRoot.getAbsoluteFile().toPath().normalize();
            Path relative = root.relativize(candidate.normalize());
            return relative.toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            // Different roots (e.g. an output dir outside the project): keep it
            // absolute rather than emit a misleading relative path.
            return candidate.normalize().toString().replace(File.separatorChar, '/');
        }
    }

    /** Sibling directory of {@code classesDir} named {@code name} (target/test-classes). */
    private static String sibling(String classesDir, String name) {
        int slash = classesDir.lastIndexOf('/');
        return slash < 0 ? name : classesDir.substring(0, slash + 1) + name;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
