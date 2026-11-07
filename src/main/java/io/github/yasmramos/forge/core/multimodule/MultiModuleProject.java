package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers and orders the modules of a multi-module project (AGENT.md section 5).
 *
 * <p>The root {@code forge.toml} declares only which directories are modules
 * ({@code [project.modules]}); each module owns its own {@code forge.toml} and
 * inherits shared settings (version, group, repositories, toolchain) from the
 * root. Modules depend on each other <em>by Maven coordinate</em>, never by path,
 * so the dependency graph is derived from each module's declared compile-scope
 * dependencies matched against the set of known module coordinates.</p>
 *
 * <p>Cycle detection is mandatory before anything is built (section 1/5): a
 * cycle makes any build order impossible, so {@link #topologicalOrder()} fails
 * with {@code FORGE-MMD-0001} rather than producing a partially ordered build.</p>
 */
public final class MultiModuleProject {

    private final File rootDirectory;
    private final ProjectConfig rootConfig;
    /** Modules in declaration order (name -> descriptor). */
    private final Map<String, ModuleDescriptor> modules = new LinkedHashMap<>();
    /** Module group:artifact -> module name, for edge derivation. */
    private final Map<String, String> gaToModule = new LinkedHashMap<>();
    /** Module name -> names of the modules it depends on (compile scope). */
    private final Map<String, Set<String>> edges = new LinkedHashMap<>();

    private MultiModuleProject(File rootDirectory, ProjectConfig rootConfig) {
        this.rootDirectory = rootDirectory;
        this.rootConfig = rootConfig;
    }

    /**
     * Discovers every module declared in the root configuration.
     *
     * @throws ForgeException {@code FORGE-MMD-0002} when a declared module has no
     *                        {@code forge.toml}, {@code FORGE-MMD-0004} when the
     *                        root declares no group to build coordinates from
     */
    public static MultiModuleProject discover(File rootDirectory, ProjectConfig rootConfig) {
        MultiModuleProject project = new MultiModuleProject(rootDirectory, rootConfig);
        project.load();
        return project;
    }

    private void load() {
        Map<String, String> declared = rootConfig.getModules();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        if (isBlank(rootConfig.getGroup())) {
            throw new ForgeException(ErrorCode.MMD_0004,
                "root forge.toml declares modules but no [project] group; "
                    + "inter-module coordinates are groupId:artifactId:version");
        }

        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String name = entry.getKey();
            File dir = moduleDirectory(name);
            File moduleToml = new File(dir, ConfigReader.CONFIG_FILE_NAME);
            if (!moduleToml.isFile()) {
                throw new ForgeException(ErrorCode.MMD_0002,
                    "module '" + name + "' has no forge.toml at " + moduleToml.getAbsolutePath());
            }
            ProjectConfig moduleConfig;
            try {
                moduleConfig = ConfigReader.loadModuleConfig(rootConfig, moduleToml);
            } catch (IOException e) {
                throw new ForgeException(ErrorCode.CFG_0001,
                    "could not read module configuration for '" + name + "': " + e.getMessage(), e);
            }

            String group = firstNonBlank(moduleConfig.getGroup(), rootConfig.getGroup());
            String version = firstNonBlank(entry.getValue(),
                moduleConfig.getVersion(), rootConfig.getVersion());

            ModuleDescriptor descriptor = new ModuleDescriptor(name, group, version, dir, moduleConfig);
            modules.put(name, descriptor);
            gaToModule.put(descriptor.getGroupArtifact(), name);
        }

        deriveEdges();
    }

    /** Resolves a module's directory: explicit {@code dir} override, else the name's last segment. */
    private File moduleDirectory(String moduleName) {
        String override = rootConfig.getModuleDirectories().get(moduleName);
        String relative = !isBlank(override) ? override : lastSegment(moduleName);
        return new File(rootDirectory, relative);
    }

    /**
     * Builds the inter-module dependency edges: a module depends on another when
     * its compile-scope dependencies name that module's coordinate.
     */
    private void deriveEdges() {
        for (Map.Entry<String, ModuleDescriptor> e : modules.entrySet()) {
            String name = e.getKey();
            ProjectConfig config = e.getValue().getConfig();
            Set<String> deps = new LinkedHashSet<>();
            for (Map.Entry<String, Object> dep : config.getDependencies().entrySet()) {
                if (!DependencyScopes.COMPILE.contains(DependencyScopes.scopeOf(dep.getValue()))) {
                    continue;
                }
                String depModule = gaToModule.get(dep.getKey());
                if (depModule != null && !depModule.equals(name)) {
                    deps.add(depModule);
                }
            }
            edges.put(name, deps);
        }
    }

    /** All modules, in declaration order. */
    public List<ModuleDescriptor> allModules() {
        return new ArrayList<>(modules.values());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    public ModuleDescriptor module(String name) {
        return modules.get(name);
    }

    /** Direct inter-module dependencies (compile scope) of the given module. */
    public Set<String> dependenciesOf(String moduleName) {
        return edges.getOrDefault(moduleName, java.util.Collections.emptySet());
    }

    /**
     * Topological order (dependencies first), with mandatory cycle detection.
     *
     * @throws ForgeException {@code FORGE-MMD-0001} naming the exact cycle
     */
    public List<ModuleDescriptor> topologicalOrder() {
        List<String> order = topologicalNames();
        List<ModuleDescriptor> out = new ArrayList<>(order.size());
        for (String name : order) {
            out.add(modules.get(name));
        }
        return out;
    }

    /**
     * Topological order restricted to {@code target} and its transitive module
     * dependencies, so {@code forge build awesome.core} builds only what it needs.
     *
     * @throws ForgeException {@code FORGE-MMD-0003} when the module is unknown
     */
    public List<ModuleDescriptor> partialOrder(String target) {
        if (!modules.containsKey(target)) {
            throw new ForgeException(ErrorCode.MMD_0003,
                "no module named '" + target + "'. Declared modules: " + modules.keySet());
        }
        Set<String> needed = new LinkedHashSet<>();
        collectTransitive(target, needed);
        List<ModuleDescriptor> out = new ArrayList<>();
        for (ModuleDescriptor m : topologicalOrder()) {
            if (needed.contains(m.getName())) {
                out.add(m);
            }
        }
        return out;
    }

    private void collectTransitive(String name, Set<String> acc) {
        if (!acc.add(name)) {
            return;
        }
        for (String dep : dependenciesOf(name)) {
            collectTransitive(dep, acc);
        }
    }

    private List<String> topologicalNames() {
        List<String> order = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        List<String> visiting = new ArrayList<>();
        for (String name : modules.keySet()) {
            visit(name, visiting, done, order);
        }
        return order;
    }

    private void visit(String name, List<String> visiting, Set<String> done, List<String> order) {
        if (done.contains(name)) {
            return;
        }
        if (visiting.contains(name)) {
            List<String> cycle = new ArrayList<>(visiting.subList(visiting.indexOf(name), visiting.size()));
            cycle.add(name);
            throw new ForgeException(ErrorCode.MMD_0001,
                "module dependency cycle: " + String.join(" -> ", cycle));
        }
        visiting.add(name);
        for (String dep : dependenciesOf(name)) {
            visit(dep, visiting, done, order);
        }
        visiting.remove(name);
        done.add(name);
        order.add(name);
    }

    private static String lastSegment(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
