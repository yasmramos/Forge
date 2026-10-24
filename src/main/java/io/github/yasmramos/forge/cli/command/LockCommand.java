package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.core.resolver.ConflictPolicy;
import io.github.yasmramos.forge.core.resolver.LockfileGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements the {@code forge lock refresh} command (AGENT.md section 7bis).
 *
 * <p>Updating the lockfile is an explicit, conscious act: a normal build never
 * rewrites it. This command resolves the declared dependencies transitively via
 * Maven Resolver (Aether), applies the fail-by-default conflict policy with any
 * {@code [resolutions]} overrides (section 2bis), and writes a canonical,
 * self-integrity-checked {@code forge.lock}.</p>
 */
public final class LockCommand {

    private static final String DEFAULT_LOCAL_REPO =
            System.getProperty("user.home") + File.separator + ".forge"
                    + File.separator + "cache" + File.separator + "deps";

    /**
     * Runs {@code forge lock refresh} from the current directory.
     *
     * @param stdout stream for human-readable progress output
     * @return 0 on success, non-zero on failure with an actionable message
     */
    public int refresh(PrintStream stdout) {
        File projectRoot = new File(".");
        File configFile = new File(projectRoot, ConfigReader.CONFIG_FILE_NAME);
        if (!configFile.isFile()) {
            System.err.println("error: no forge.toml found in " + projectRoot.getAbsolutePath());
            System.err.println("fix: run this command from a Forge project root, or use `forge init`.");
            return 1;
        }
        try {
            ProjectConfig config = ConfigReader.loadConfig(configFile);
            Map<String, String> repositories = config.getRepositories().isEmpty()
                    ? defaultRepositories()
                    : config.getRepositories();

            LockfileGenerator generator =
                    new LockfileGenerator(new File(DEFAULT_LOCAL_REPO), repositories);
            // Explicit regeneration: skip the stale-lock guard (that guard belongs
            // to normal builds, per AGENT.md section 7bis).
            Lockfile lock = generator.generate(projectRoot, config, false);
            lock.write(projectRoot);

            stdout.println("forge.lock refreshed: " + lock.getDependencies().size()
                    + " locked dependencies (config hash " + shortHash(lock.getConfigHash()) + ").");
            stdout.println("Commit forge.lock so every machine and CI job builds the same graph.");
            return 0;
        } catch (ConflictPolicy.ConflictException e) {
            // Section 2bis: show who brings what BEFORE suggesting a fix.
            System.err.println("error FORGE-RSL-0001: unaligned version conflicts detected.");
            for (ConflictPolicy.Conflict c : e.getConflicts()) {
                System.err.println("  - " + c);
            }
            System.err.println("fix: add an exact version under [resolutions] in forge.toml");
            System.err.println("     (e.g. \"org.slf4j:slf4j-api\" = \"2.0.7\"), align via a BOM,");
            System.err.println("     then run `forge lock refresh` again.");
            return 1;
        } catch (AetherDependencyResolver.ResolutionFailedException e) {
            System.err.println("error: dependency resolution failed.");
            System.err.println("cause: " + e.getMessage());
            System.err.println("fix: check coordinates/versions in forge.toml and that the");
            System.err.println("     repository URLs in [repositories] are reachable.");
            return 1;
        } catch (Exception e) {
            System.err.println("error: could not refresh the lockfile.");
            System.err.println("cause: " + e.getMessage());
            System.err.println("fix: re-run with --debug for the full stack trace.");
            return 1;
        }
    }

    private static Map<String, String> defaultRepositories() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("central", "https://repo1.maven.org/maven2");
        return repos;
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
