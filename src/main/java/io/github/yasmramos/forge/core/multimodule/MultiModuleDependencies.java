package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.core.resolver.LockfileGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Aggregates the dependency view of a multi-module project (AGENT.md sections
 * 5, 7bis and 8bis).
 *
 * <p>In a multi-module project the root {@code forge.toml} carries no
 * dependencies of its own, so resolving "the project's dependencies" means
 * resolving the union of every module's direct compile-scope dependencies.
 * Inter-module coordinates are deliberately excluded: a module is part of the
 * project, not something to download, and its own external dependencies are
 * already collected because that module is itself in the union. That makes the
 * union's transitive closure equal to the project's, without requiring the
 * modules to have been built first.</p>
 *
 * <p>The single root lock (section 8bis) is tied to the whole project through a
 * combined hash of the root and every module configuration, keyed by relative
 * path so the digest is identical across machines and checkouts (section 17).</p>
 */
public final class MultiModuleDependencies {

    private MultiModuleDependencies() {
    }

    /** True when the configuration declares modules, i.e. this is a multi-module project. */
    public static boolean isMultiModule(ProjectConfig config) {
        return config.getModules() != null && !config.getModules().isEmpty();
    }

    /**
     * Union of every module's direct compile-scope dependencies, excluding
     * inter-module coordinates. Sorted and de-duplicated so the resulting root
     * set — and therefore the resolved graph and lock — is deterministic.
     */
    public static List<String> compileRoots(MultiModuleProject project) {
        TreeSet<String> roots = new TreeSet<>();
        for (ModuleDescriptor module : project.allModules()) {
            ProjectConfig config = module.getConfig();
            for (Map.Entry<String, Object> dep : config.getDependencies().entrySet()) {
                if (!DependencyScopes.COMPILE.contains(DependencyScopes.scopeOf(dep.getValue()))) {
                    continue;
                }
                if (project.moduleForCoordinate(dep.getKey()) != null) {
                    continue; // inter-module: built locally, never locked as external
                }
                String version = DependencyScopes.versionOf(dep.getValue());
                if (version != null && !version.trim().isEmpty()) {
                    roots.add(dep.getKey() + ":" + version);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * The root and every module {@code forge.toml}, keyed by a relative logical
     * path ({@code forge.toml}, {@code app/forge.toml}), for the combined
     * multi-module config hash.
     */
    public static SortedMap<String, File> namedConfigs(File rootDirectory,
                                                       MultiModuleProject project) {
        SortedMap<String, File> configs = new TreeMap<>();
        configs.put("forge.toml", new File(rootDirectory, "forge.toml"));
        for (ModuleDescriptor module : project.allModules()) {
            String relative = relativePath(rootDirectory, module.getDirectory()) + "/forge.toml";
            configs.put(relative, new File(module.getDirectory(), "forge.toml"));
        }
        return configs;
    }

    /** Repository set: the root's, falling back to Central when empty. */
    public static Map<String, String> repositories(ProjectConfig rootConfig) {
        return rootConfig.getRepositories();
    }

    /**
     * Resolves the union of the modules' compile dependencies transitively, with
     * inter-module coordinates served from the workspace. This is the graph that
     * backs {@code forge tree}/{@code why}/{@code resolve} and the root lock in a
     * multi-module project (AGENT.md sections 5 and 8bis).
     */
    public static AetherDependencyResolver.ResolvedGraph resolve(MultiModuleProject project,
                                                                 File rootDirectory,
                                                                 Map<String, String> repositories) {
        List<String> roots = compileRoots(project);
        File workspaceDir = new File(rootDirectory, ".forge/workspace");
        org.eclipse.aether.repository.WorkspaceReader workspace =
            new WorkspaceModuleRepository(workspaceDir, moduleCoordinates(project));
        AetherDependencyResolver resolver = new AetherDependencyResolver(
            localCacheDir(), repositories, workspace);
        return resolver.resolveTransitive(roots);
    }

    /**
     * Generates the single root lock covering the whole multi-module graph
     * (AGENT.md section 8bis), tied to a combined hash of the root and every
     * module configuration.
     */
    public static Lockfile generateLock(MultiModuleProject project, File rootDirectory,
                                        ProjectConfig rootConfig, Map<String, String> repositories)
            throws Exception {
        String configHash = Lockfile.hashOf(namedConfigs(rootDirectory, project));
        File workspaceDir = new File(rootDirectory, ".forge/workspace");
        org.eclipse.aether.repository.WorkspaceReader workspace =
            new WorkspaceModuleRepository(workspaceDir, moduleCoordinates(project));
        LockfileGenerator generator = new LockfileGenerator(
            localCacheDir(), repositories, workspace);
        return generator.lock(compileRoots(project), configHash,
            rootConfig.getResolutions(), rootConfig.getConstraints());
    }

    /**
     * Verifies the root lock against the current multi-module configuration.
     * Called before each module build so a normal build never silently
     * re-resolves (AGENT.md sections 7bis and 8bis). A missing lock is allowed
     * (bootstrap), mirroring the single-module behavior.
     */
    public static void enforceRootLock(MultiModuleProject project, File rootDirectory)
            throws Exception {
        if (!new File(rootDirectory, Lockfile.FILE_NAME).isFile()) {
            return;
        }
        Lockfile existing = Lockfile.read(rootDirectory);
        existing.verifyAgainstHash(Lockfile.hashOf(namedConfigs(rootDirectory, project)));
    }

    /** Coordinates of every module, for the workspace reader guard. */
    public static java.util.Set<String> moduleCoordinates(MultiModuleProject project) {
        java.util.Set<String> coordinates = new TreeSet<>();
        for (ModuleDescriptor module : project.allModules()) {
            coordinates.add(module.getGroupArtifact());
        }
        return coordinates;
    }

    private static File localCacheDir() {
        return new File(System.getProperty("user.home"),
            ".forge/cache/deps");
    }

    private static String relativePath(File root, File moduleDir) {
        try {
            return root.toPath().toAbsolutePath().normalize()
                .relativize(moduleDir.toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        } catch (Exception e) {
            return moduleDir.getName();
        }
    }
}
