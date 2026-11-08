package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.core.multimodule.MultiModuleDependencies;
import io.github.yasmramos.forge.core.multimodule.MultiModuleProject;
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
     * @return 0 on success
     * @throws ForgeException with a registered catalog code on any failure
     *         (AGENT.md section 10bis), so the CLI renders what/why/how
     *         uniformly and honours {@code --format=json}.
     */
    public int refresh(PrintStream stdout) {
        return refresh(stdout, null);
    }

    /**
     * As {@link #refresh(PrintStream)} but for an explicitly selected variant
     * (AGENT.md sections 8 and 8bis): the regenerated lock records the variant
     * in its {@code config_hash}, so a later build under a different variant
     * fails loudly instead of resolving differently.
     */
    public int refresh(PrintStream stdout, String variant) {
        File projectRoot = new File(".");
        File configFile = new File(projectRoot, ConfigReader.CONFIG_FILE_NAME);
        if (!configFile.isFile()) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "no forge.toml found in " + projectRoot.getAbsolutePath());
        }
        try {
            ProjectConfig config = ConfigReader.loadConfig(configFile, null, variant);
            Map<String, String> repositories = config.getRepositories().isEmpty()
                    ? defaultRepositories()
                    : config.getRepositories();

            // Multi-module (AGENT.md sections 5 and 8bis): the single root lock
            // covers the union of every module's dependencies, tied to a combined
            // hash of the root and all module configs.
            if (MultiModuleDependencies.isMultiModule(config)) {
                MultiModuleProject project = MultiModuleProject.discover(projectRoot, config, variant);
                Lockfile lock = MultiModuleDependencies.generateLock(
                        project, projectRoot, config, repositories);
                lock.write(projectRoot);
                stdout.println("forge.lock refreshed: " + lock.getDependencies().size()
                        + " locked dependencies (config hash " + shortHash(lock.getConfigHash()) + ").");
                stdout.println("Commit forge.lock so every machine and CI job builds the same graph.");
                return 0;
            }

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
            // Section 2bis: the conflict exception already carries a structured
            // FORGE-DEP-0001 error listing who brings what before the fix.
            throw e.toForgeException();
        } catch (AetherDependencyResolver.ResolutionFailedException e) {
            throw new ForgeException(ErrorCode.DEP_0002, e.getMessage(), e);
        } catch (ForgeException e) {
            throw e;
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.INT_0001, e.getMessage(), e);
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
