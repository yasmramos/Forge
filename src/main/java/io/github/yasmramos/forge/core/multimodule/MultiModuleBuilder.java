package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Builds a multi-module project (AGENT.md section 5).
 *
 * <p>Order and parallelism follow the inter-module dependency graph: modules are
 * grouped into levels (a module's level is one past the deepest module it
 * depends on), levels run sequentially, and modules inside a level run in
 * parallel up to the configured thread limit. Each module is built exactly like a
 * standalone project — the same {@link ForgeEngine} pipeline — and its jar is then
 * installed into the shared workspace, so the next level resolves it by
 * coordinate.</p>
 *
 * <p>A partial build ({@code forge build awesome.core}) builds only the target
 * module and its transitive dependencies. A cycle is rejected up front with
 * {@code FORGE-MMD-0001} before any module is touched.</p>
 */
public final class MultiModuleBuilder {

    private final File rootDirectory;
    private final ProjectConfig rootConfig;

    public MultiModuleBuilder(File rootDirectory, ProjectConfig rootConfig) {
        this.rootDirectory = rootDirectory;
        this.rootConfig = rootConfig;
    }

    /** Outcome of building one module. */
    public static final class ModuleResult {
        private final String name;
        private final boolean success;
        private final String jarPath;
        private final String error;

        ModuleResult(String name, boolean success, String jarPath, String error) {
            this.name = name;
            this.success = success;
            this.jarPath = jarPath;
            this.error = error;
        }

        public String getName() { return name; }
        public boolean isSuccess() { return success; }
        public String getJarPath() { return jarPath; }
        public String getError() { return error; }
    }

    /** Outcome of the whole multi-module build. */
    public static final class Report {
        private final List<ModuleResult> results;

        Report(List<ModuleResult> results) {
            this.results = results;
        }

        public List<ModuleResult> getResults() { return results; }

        public boolean isSuccess() {
            return results.stream().allMatch(ModuleResult::isSuccess);
        }
    }

    /**
     * Builds the project, or only {@code targetModule} and its dependencies when
     * a target is given.
     *
     * @param targetModule module to build partially, or null for the whole project
     */
    public Report build(String targetModule) {
        MultiModuleProject project = MultiModuleProject.discover(
            rootDirectory, rootConfig, rootConfig.getSelectedVariant());
        if (project.isEmpty()) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0001,
                "no [project.modules] declared; this is not a multi-module project");
        }

        // Section 8bis: a variant declared by some modules but not all is a
        // notice, never silence. The modules lacking it build with their base
        // configuration, and the user is told exactly which ones.
        java.util.List<String> ignoring = project.modulesIgnoringVariant();
        if (!ignoring.isEmpty()) {
            System.err.println("notice: variant '" + project.getVariant()
                + "' is not declared by: " + String.join(", ", ignoring)
                + " (these modules build with their base configuration)");
        }

        List<ModuleDescriptor> selected = targetModule == null
            ? project.topologicalOrder()
            : project.partialOrder(targetModule);

        // Reproducibility (AGENT.md sections 7bis and 8bis): the single root lock
        // must match the whole multi-module configuration before anything runs.
        try {
            MultiModuleDependencies.enforceRootLock(project, rootDirectory);
        } catch (io.github.yasmramos.forge.cli.error.ForgeException e) {
            throw e;
        } catch (Exception e) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.INT_0001, e.getMessage(), e);
        }

        File workspaceDir = new File(rootDirectory, ".forge/workspace");
        // Restrict the workspace reader to the coordinates of this build's
        // modules, so an unrelated dependency that shares an artifactId/version
        // can never be served a module's jar.
        java.util.Set<String> moduleCoordinates = MultiModuleDependencies.moduleCoordinates(project);
        WorkspaceModuleRepository workspace =
            new WorkspaceModuleRepository(workspaceDir, moduleCoordinates);

        List<ModuleResult> results = new ArrayList<>();
        int threads = Math.max(1, rootConfig.getBuildSettings().getThreads());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (List<ModuleDescriptor> level : levels(project, selected)) {
                List<Future<ModuleResult>> futures = new ArrayList<>();
                for (ModuleDescriptor m : level) {
                    futures.add(pool.submit(new Callable<ModuleResult>() {
                        @Override
                        public ModuleResult call() {
                            return buildModule(m, workspace, moduleCoordinates);
                        }
                    }));
                }
                for (Future<ModuleResult> f : futures) {
                    results.add(f.get());
                }
                // Stop the whole build as soon as a level has a failure: later
                // levels depend on the artifacts this level should have installed.
                boolean levelFailed = false;
                for (int i = results.size() - level.size(); i < results.size(); i++) {
                    if (!results.get(i).isSuccess()) {
                        levelFailed = true;
                    }
                }
                if (levelFailed) {
                    break;
                }
            }
        } catch (Exception e) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.INT_0001, e.getMessage(), e);
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        return new Report(results);
    }

    /** Builds one module and installs its jar + POM into the workspace. */
    private ModuleResult buildModule(ModuleDescriptor module, WorkspaceModuleRepository workspace,
                                     java.util.Set<String> moduleCoordinates) {
        ProjectConfig config = preparedConfig(module, workspace.getDirectory(), moduleCoordinates);

        ForgeEngine engine = new ForgeEngine(config, module.getDirectory());
        ForgeEngine.BuildResult result = engine.build();
        if (!result.isSuccess()) {
            String reason = result.getError() != null
                ? result.getError().getMessage()
                : "build failed";
            return new ModuleResult(module.getName(), false, null, reason);
        }

        try {
            Path jar = moduleJar(config);
            if (!Files.isRegularFile(jar)) {
                return new ModuleResult(module.getName(), false, null,
                    "expected packaged jar at " + jar);
            }
            Path pom = Files.createTempFile(module.getName() + "-", ".pom");
            Files.write(pom, ModulePom.generate(config).getBytes(StandardCharsets.UTF_8));
            workspace.install(module.getName(), module.getVersion(), jar.toFile(), pom.toFile());
            Files.deleteIfExists(pom);
            return new ModuleResult(module.getName(), true, jar.toString(), null);
        } catch (Exception e) {
            return new ModuleResult(module.getName(), false, null,
                "could not install module artifact: " + e.getMessage());
        }
    }

    /** Packaged jar path, mirroring {@code ForgeEngine}'s build-base convention. */
    private static Path moduleJar(ProjectConfig config) {
        String output = config.getOutputDirectory();
        Path classes = Paths.get(output).toAbsolutePath().normalize();
        Path base = classes.getParent() != null ? classes.getParent() : classes;
        return base.resolve("forge-output").resolve("forge-main.jar");
    }

    /**
     * Prepares a module's configuration for building or running: points it at the
     * shared workspace so sibling modules resolve by coordinate, and anchors its
     * relative paths at the module directory.
     */
    public static ProjectConfig preparedConfig(ModuleDescriptor module, File workspaceDir) {
        return preparedConfig(module, workspaceDir, java.util.Collections.<String>emptySet());
    }

    /**
     * As {@link #preparedConfig(ModuleDescriptor, File)}, also restricting the
     * workspace reader to the given module coordinates (AGENT.md section 5).
     */
    public static ProjectConfig preparedConfig(ModuleDescriptor module, File workspaceDir,
                                               java.util.Set<String> moduleCoordinates) {
        ProjectConfig config = module.getConfig();
        config.setWorkspaceDirectory(workspaceDir.getAbsolutePath());
        config.setWorkspaceModuleCoordinates(moduleCoordinates);
        rebasePaths(config, module.getDirectory());
        return config;
    }

    /**
     * Rewrites the module's source/output paths so they are anchored at the
     * module directory instead of the process working directory. Without this,
     * every module would compile into the root's {@code target/} tree.
     */
    private static void rebasePaths(ProjectConfig config, File moduleDir) {
        Path base = moduleDir.toPath().toAbsolutePath().normalize();
        config.setOutputDirectory(resolve(base, config.getOutputDirectory(), "target/classes"));
        config.setSourceDirectory(resolve(base, config.getSourceDirectory(), "src/main/java"));
        config.setMainJavaSources(rebase(base, config.getMainJavaSources()));
        config.setMainResourcePaths(rebase(base, config.getMainResourcePaths()));
        config.setTestJavaSources(rebase(base, config.getTestJavaSources()));
        config.setTestResourcePaths(rebase(base, config.getTestResourcePaths()));
        config.setSourcePaths(rebase(base, config.getSourcePaths()));
    }

    private static String resolve(Path base, String value, String fallback) {
        String effective = (value == null || value.trim().isEmpty()) ? fallback : value;
        return resolvePath(base, effective).toString();
    }

    private static List<String> rebase(Path base, List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                out.add(resolvePath(base, v).toString());
            }
        }
        return out;
    }

    private static Path resolvePath(Path base, String value) {
        Path p = Paths.get(value);
        return p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
    }

    /**
     * Groups the selected modules into levels: level 0 has no in-set dependency,
     * level N depends only on levels &lt; N. Modules within a level are mutually
     * independent, so they can build in parallel.
     */
    private List<List<ModuleDescriptor>> levels(MultiModuleProject project,
                                                List<ModuleDescriptor> selected) {
        Set<String> inSet = new java.util.LinkedHashSet<>();
        for (ModuleDescriptor m : selected) {
            inSet.add(m.getName());
        }
        Map<String, Integer> level = new LinkedHashMap<>();
        for (ModuleDescriptor m : selected) {
            computeLevel(m.getName(), project, inSet, level);
        }
        // Preserve topological order within each level for deterministic output.
        Map<Integer, List<ModuleDescriptor>> grouped = new java.util.TreeMap<>();
        for (ModuleDescriptor m : selected) {
            grouped.computeIfAbsent(level.get(m.getName()), k -> new ArrayList<>()).add(m);
        }
        return new ArrayList<>(grouped.values());
    }

    private int computeLevel(String name, MultiModuleProject project, Set<String> inSet,
                             Map<String, Integer> level) {
        Integer cached = level.get(name);
        if (cached != null) {
            return cached;
        }
        int max = -1;
        for (String dep : project.dependenciesOf(name)) {
            if (inSet.contains(dep)) {
                max = Math.max(max, computeLevel(dep, project, inSet, level));
            }
        }
        int value = max + 1;
        level.put(name, value);
        return value;
    }
}
