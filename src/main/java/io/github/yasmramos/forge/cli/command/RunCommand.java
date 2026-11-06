package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code forge run} - execute the project's main class.
 *
 * <p>Per AGENT.md section 10, {@code run} is a command, not a task in the
 * graph: it owns stdout as a direct passthrough of the executed program,
 * while Forge's own logs go to stderr. The compiled classes must already
 * exist (run {@code forge build} first); this command deliberately does
 * not trigger a build so its behavior stays predictable.</p>
 */
public final class RunCommand {

    private RunCommand() {
    }

    /**
     * Executes the configured main class with the compile/runtime
     * classpath and any JVM arguments declared in [exec].
     *
     * @param engine   engine holding the loaded project configuration
     * @param userArgs extra arguments passed after "--" on the CLI; they
     *                 take precedence over the static [exec] args
     * @return exit code of the launched process
     */
    public static int run(ForgeEngine engine, List<String> userArgs) throws IOException, InterruptedException {
        ProjectConfig config = engine.getConfig();

        String mainClass = config.getMainClass();
        if (mainClass == null || mainClass.trim().isEmpty()) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.RUN_0001,
                "no [exec] main is set in forge.toml");
        }

        Path classesDir = resolveClassesDir(config);
        if (!classesDir.toFile().isDirectory()) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.RUN_0002,
                "expected compiled classes at " + classesDir);
        }

        List<String> command = new ArrayList<>();
        command.add(currentJava());
        if (config.getJvmArgs() != null) {
            command.addAll(config.getJvmArgs());
        }
        command.add("-cp");
        List<String> dependencyJars;
        try {
            dependencyJars = resolveDependencyJars(config);
        } catch (Exception e) {
            System.err.println("forge run warning: dependency resolution failed ("
                + e.getMessage() + "); continuing with classes dir only.");
            dependencyJars = new ArrayList<>();
        }
        command.add(buildRuntimeClasspath(classesDir, dependencyJars));
        command.add(mainClass);

        // User-supplied args override the static ones from [exec].
        if (userArgs != null && !userArgs.isEmpty()) {
            command.addAll(userArgs);
        } else if (config.getExecArgs() != null) {
            command.addAll(config.getExecArgs());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO(); // stdout/stderr belong to the program, per AGENT.md §11
        Process process = pb.start();
        return process.waitFor();
    }

    /** Compiled-classes directory following Maven convention (target/classes). */
    private static Path resolveClassesDir(ProjectConfig config) {
        String configured = config.getOutputDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            File f = new File(configured);
            return f.isAbsolute() ? f.toPath() : Paths.get(System.getProperty("user.dir"), configured);
        }
        return Paths.get(System.getProperty("user.dir"), "target", "classes");
    }

    /** Classes dir plus every resolved dependency jar for the run classpath. */
    private static String buildRuntimeClasspath(Path classesDir, List<String> dependencyJars) {
        StringBuilder cp = new StringBuilder(classesDir.toString());
        for (String jar : dependencyJars) {
            cp.append(File.pathSeparator).append(jar);
        }
        return cp.toString();
    }

    /**
     * Resolve compile+runtime dependencies through the shared cache in
     * ~/.forge/cache/deps (already populated by {@code forge build} or
     * {@code forge lock refresh}). Resolution is best-effort here: if a
     * coordinate cannot be fetched at run time we warn and keep going with
     * whatever was found, since the build itself already validated them.
     */
    private static List<String> resolveDependencyJars(ProjectConfig config) {
        List<String> jars = new ArrayList<>();
        java.util.Map<String, Object> declared = config.getDependencies();
        if (declared == null || declared.isEmpty()) {
            return jars;
        }
        List<String> roots = new ArrayList<>();
        for (java.util.Map.Entry<String, Object> entry : declared.entrySet()) {
            // The run classpath is compile + provided + runtime; test is excluded.
            if (!io.github.yasmramos.forge.core.DependencyScopes.RUNTIME
                    .contains(io.github.yasmramos.forge.core.DependencyScopes.scopeOf(entry.getValue()))) {
                continue;
            }
            String version = io.github.yasmramos.forge.core.DependencyScopes.versionOf(entry.getValue());
            if (version != null && !version.trim().isEmpty()) {
                roots.add(entry.getKey() + ":" + version);
            }
        }
        File localCacheDir = Paths.get(System.getProperty("user.home"),
            ".forge", "cache", "deps").toFile();
        io.github.yasmramos.forge.core.resolver.AetherDependencyResolver aether =
            new io.github.yasmramos.forge.core.resolver.AetherDependencyResolver(
                localCacheDir, config.getRepositories());
        io.github.yasmramos.forge.core.resolver.AetherDependencyResolver.ResolvedGraph graph =
            aether.resolveTransitive(roots);
        for (io.github.yasmramos.forge.core.resolver.AetherDependencyResolver.ResolvedArtifact artifact
                : graph.getArtifacts()) {
            jars.add(artifact.getFile().getAbsolutePath());
        }
        return jars;
    }

    /** Absolute path to the java executable of the current JVM. */
    private static String currentJava() {
        String home = System.getProperty("java.home");
        return Paths.get(home, "bin", "java").toString();
    }
}
