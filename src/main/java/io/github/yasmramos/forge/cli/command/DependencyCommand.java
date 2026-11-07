package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.DependencyScopes;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.core.resolver.ConflictPolicy;
import io.github.yasmramos.forge.core.resolver.DependencyTree;
import io.github.yasmramos.forge.core.resolver.DependencyTreeRenderer;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Dependency inspection commands: {@code forge tree}, {@code forge why} and
 * {@code forge resolve --explain} (AGENT.md section 10).
 *
 * <p>All three read the same resolved graph, so the answers cannot disagree.
 * The conflict policy is fail-by-default (section 2bis); {@code resolve
 * --explain} exists precisely so that failure comes with guidance instead of
 * being pure friction: it shows who requests each version and which lever
 * would settle the conflict.</p>
 */
public final class DependencyCommand {

    private static final String DEFAULT_LOCAL_REPO =
            System.getProperty("user.home") + File.separator + ".forge"
                    + File.separator + "cache" + File.separator + "deps";

    private DependencyCommand() {
    }

    /** {@code forge tree [--depth=N] [filter]} */
    public static int tree(String[] args, PrintStream out) {
        int depth = parseDepth(args);
        String filter = firstNonFlag(args);
        AetherDependencyResolver.ResolvedGraph graph = resolveCompileGraph();
        if (filter != null && !hasMatch(graph.getTree(), filter)) {
            out.println("No dependency matches '" + filter + "'.");
            return 0;
        }
        out.print(new DependencyTreeRenderer(depth, filter).render(graph.getTree()));
        return 0;
    }

    /** {@code forge why <coordinate>} */
    public static int why(String[] args, PrintStream out) {
        String query = firstNonFlag(args);
        if (query == null) {
            throw new ForgeException(ErrorCode.CFG_0002,
                "usage: forge why <group:artifact[:version]|artifactId>");
        }
        AetherDependencyResolver.ResolvedGraph graph = resolveCompileGraph();
        List<List<DependencyTree.Node>> paths = graph.getTree().pathsTo(query);
        if (paths.isEmpty()) {
            out.println("'" + query + "' is not in the resolved graph.");
            return 0;
        }
        out.println(query + " is present through " + paths.size()
            + (paths.size() == 1 ? " path:" : " paths:"));
        for (List<DependencyTree.Node> path : paths) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) {
                    sb.append(" -> ");
                }
                sb.append(path.get(i).getCoordinate());
            }
            out.println(sb);
        }
        out.println();
        out.println("The lockfile pins exactly one version per artifact; [resolutions]");
        out.println("in forge.toml overrides every path above at lock time.");
        out.println("Run 'forge resolve --explain' to see the full version derivation.");
        return 0;
    }

    /**
     * {@code forge resolve [--explain]}. Without {@code --explain} it prints the
     * effective selection; with it, the full derivation per conflicted coordinate.
     */
    public static int resolve(String[] args, PrintStream out) {
        boolean explain = false;
        for (String a : args) {
            if ("--explain".equals(a)) {
                explain = true;
            }
        }
        AetherDependencyResolver.ResolvedGraph graph = resolveCompileGraph();
        Map<String, List<String>> versionsByGa = graph.getRequestedVersionsByGa();
        ProjectConfig config = loadConfig();
        ConflictPolicy policy = new ConflictPolicy(config.getResolutions());
        List<String> conflicts = conflictedGAs(versionsByGa);

        if (!explain) {
            // Plain `forge resolve` reports the effective selection and fails on
            // unresolved conflicts, exactly like a lock refresh would. Convert to
            // the structured FORGE-DEP-0001 error so the CLI renders what/why/how
            // instead of leaking a stack trace (AGENT.md sections 2bis and 10bis).
            Map<String, String> chosen;
            try {
                chosen = policy.enforce(versionsByGa);
            } catch (ConflictPolicy.ConflictException e) {
                throw e.toForgeException();
            }
            out.println("Resolved " + chosen.size() + " artifact(s) for the compile classpath.");
            return 0;
        }

        // `--explain` diagnoses instead of failing: it must print the derivation
        // even when the conflict policy would otherwise abort (AGENT.md section 2bis).
        out.println("Version derivation (AGENT.md section 2bis)");
        out.println();
        if (conflicts.isEmpty()) {
            out.println("No version conflicts: every artifact resolves to a single version.");
            return 0;
        }
        for (String ga : conflicts) {
            TreeSet<String> versions = new TreeSet<>(versionsByGa.get(ga));
            String forced = policy.forcedVersion(ga);
            out.println(ga + " requested in " + versions);
            for (String v : versions) {
                out.println("  " + v + " requested by: " + describeRequesters(graph.getTree(), ga, v));
            }
            if (forced != null) {
                out.println("  -> [resolutions] pins " + forced + " (explicit user decision wins)");
            } else {
                out.println("  -> no winner: Forge fails by default instead of picking one");
                out.println("     fix: add \"" + ga + "\" = \"<version>\" under [resolutions]"
                    + " in forge.toml, or align versions via a BOM, then run 'forge lock refresh'");
            }
            out.println();
        }
        return 0;
    }

    // --- shared plumbing ---

    private static AetherDependencyResolver.ResolvedGraph resolveCompileGraph() {
        ProjectConfig config = loadConfig();
        Map<String, String> repositories = config.getRepositories().isEmpty()
            ? defaultRepositories() : config.getRepositories();
        AetherDependencyResolver resolver =
            new AetherDependencyResolver(new File(DEFAULT_LOCAL_REPO), repositories);
        try {
            return resolver.resolveTransitive(compileRoots(config));
        } catch (AetherDependencyResolver.ResolutionFailedException e) {
            throw new ForgeException(ErrorCode.DEP_0002, e.getMessage(), e);
        }
    }

    private static ProjectConfig loadConfig() {
        File configFile = new File(ConfigReader.CONFIG_FILE_NAME);
        if (!configFile.isFile()) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "no forge.toml found in " + configFile.getAbsolutePath());
        }
        try {
            return ConfigReader.loadConfig(configFile);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.CFG_0001, e.getMessage(), e);
        }
    }

    private static List<String> compileRoots(ProjectConfig config) {
        List<String> roots = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.getDependencies().entrySet()) {
            if (!DependencyScopes.COMPILE.contains(DependencyScopes.scopeOf(e.getValue()))) {
                continue;
            }
            String version = DependencyScopes.versionOf(e.getValue());
            if (version != null && !version.trim().isEmpty()) {
                roots.add(e.getKey() + ":" + version);
            }
        }
        return roots;
    }

    private static List<String> conflictedGAs(Map<String, List<String>> versionsByGa) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : versionsByGa.entrySet()) {
            if (new TreeSet<>(e.getValue()).size() > 1) {
                out.add(e.getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static String describeRequesters(DependencyTree tree, String ga, String version) {
        List<String> parents = new ArrayList<>();
        for (List<DependencyTree.Node> path : tree.pathsTo(ga)) {
            DependencyTree.Node leaf = path.get(path.size() - 1);
            if (!version.equals(leaf.getVersion())) {
                continue;
            }
            String parent = path.size() >= 2
                ? path.get(path.size() - 2).getCoordinate()
                : "(direct)";
            if (!parents.contains(parent)) {
                parents.add(parent);
            }
        }
        return parents.isEmpty() ? "(direct)" : String.join(", ", parents);
    }

    private static boolean hasMatch(DependencyTree tree, String filter) {
        for (DependencyTree.Node n : tree.flatten()) {
            if (n.matchesFilter(filter)) {
                return true;
            }
        }
        return false;
    }

    private static int parseDepth(String[] args) {
        for (String a : args) {
            if (a.startsWith("--depth=")) {
                try {
                    return Integer.parseInt(a.substring("--depth=".length()).trim());
                } catch (NumberFormatException e) {
                    throw new ForgeException(ErrorCode.CFG_0002,
                        "--depth expects an integer, got '" + a + "'");
                }
            }
        }
        return 0; // unlimited
    }

    private static String firstNonFlag(String[] args) {
        for (String a : args) {
            if (!a.startsWith("--")) {
                return a;
            }
        }
        return null;
    }

    private static Map<String, String> defaultRepositories() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        return repos;
    }
}
