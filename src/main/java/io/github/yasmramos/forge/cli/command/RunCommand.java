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
            System.err.println("forge run failed");
            System.err.println("What: no main class configured.");
            System.err.println("Why:  'forge run' needs an entry point to launch.");
            System.err.println("Fix:  add [exec] main = \"com.example.Main\" to forge.toml.");
            return 1;
        }

        Path classesDir = resolveClassesDir(config);
        if (!classesDir.toFile().isDirectory()) {
            System.err.println("forge run failed");
            System.err.println("What: compiled classes not found at " + classesDir + ".");
            System.err.println("Why:  the project has not been built yet.");
            System.err.println("Fix:  run 'forge build' before 'forge run'.");
            return 1;
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
            String version = null;
            if (entry.getValue() instanceof String) {
                version = (String) entry.getValue();
            } else if (entry.getValue() instanceof java.util.Map) {
                Object v = ((java.util.Map<?, ?>) entry.getValue()).get("version");
                if (v != null) {
                    version = String.valueOf(v);
                }
            }
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
