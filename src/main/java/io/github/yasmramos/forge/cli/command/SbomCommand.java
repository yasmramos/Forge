package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.core.sbom.SbomGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code forge sbom} - generate a CycloneDX SBOM (AGENT.md section 18).
 *
 * <p>SBOMs are produced by default during {@code forge publish}; this command
 * offers the same document on demand, writing it next to the build output
 * ({@code target/sbom.json}) unless {@code --output=<path>} is given. Hashes
 * come from the lockfile when present (so the SBOM matches what the build
 * resolved) and are computed from the local artifacts otherwise.</p>
 */
public final class SbomCommand {

    /** Relative location of the generated document under the build directory. */
    public static final String DEFAULT_FILE = "target/sbom.json";

    private SbomCommand() {
    }

    public static int run(ForgeEngine engine, String[] args, PrintStream out) {
        if (engine == null) {
            throw new ForgeException(ErrorCode.CFG_0001, "forge sbom requires a project (no forge.toml found)");
        }
        ProjectConfig config = engine.getConfig();
        File projectRoot = engine.getProjectRoot();

        AetherDependencyResolver.ResolvedGraph graph = DependencyCommand.resolveCompileGraph();
        Map<String, Lockfile.LockedDependency> locked = readLockfile(projectRoot);

        List<SbomGenerator.Component> components = new ArrayList<>();
        for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
            String[] ga = splitCoordinates(a.getCoordinates());
            String sha = lockedSha(locked, ga[0] + ":" + ga[1]);
            if (sha == null) {
                sha = SbomGenerator.sha256Of(a.getFile());
            }
            components.add(new SbomGenerator.Component(
                ga[0], ga[1], ga[2], scopeOf(a.getScope()), sha));
        }

        // A fixed timestamp keeps the document reproducible (section 17); the
        // SOURCE_DATE_EPOCH chain already defines the project's canonical time.
        Instant timestamp = Instant.ofEpochSecond(
            io.github.yasmramos.forge.core.packageinfo.ReproducibleBuild.epochSeconds(
                projectRoot, config.getBuildSettings().getReproducibleTimestamp()));

        String json = SbomGenerator.generate(
            config.getName() == null ? "unknown" : config.getName(),
            config.getVersion() == null ? "0.0.0" : config.getVersion(),
            components, timestamp);

        File output = outputFile(projectRoot, args);
        try {
            File parent = output.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.INT_0001,
                "could not write SBOM to " + output.getAbsolutePath() + ": " + e.getMessage(), e);
        }

        out.println("CycloneDX SBOM written to " + output.getAbsolutePath()
            + " (" + components.size() + " components)");
        return 0;
    }

    private static Map<String, Lockfile.LockedDependency> readLockfile(File projectRoot) {
        File lock = new File(projectRoot, Lockfile.FILE_NAME);
        if (!lock.isFile()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return Lockfile.read(projectRoot).getDependencies();
        } catch (Exception e) {
            // A broken lock is the build's problem (FORGE-LCK-*); the SBOM can
            // still be produced from local artifacts, so fall back silently.
            return java.util.Collections.emptyMap();
        }
    }

    private static String lockedSha(Map<String, Lockfile.LockedDependency> locked, String ga) {
        Lockfile.LockedDependency d = locked.get(ga);
        return d == null ? null : d.sha256;
    }

    /** {@code group:artifact:version} -> [group, artifact, version]. */
    private static String[] splitCoordinates(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length >= 3) {
            return new String[] { parts[0], parts[1], parts[2] };
        }
        if (parts.length == 2) {
            return new String[] { parts[0], parts[1], "" };
        }
        return new String[] { "", coordinates, "" };
    }

    private static String scopeOf(String scope) {
        return scope == null || scope.trim().isEmpty() ? "compile" : scope;
    }

    private static File outputFile(File projectRoot, String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--output=")) {
                File f = new File(arg.substring("--output=".length()));
                return f.isAbsolute() ? f : new File(projectRoot, f.getPath());
            }
        }
        return new File(projectRoot, DEFAULT_FILE);
    }
}
