package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.lockfile.Lockfile;
import io.github.yasmramos.forge.core.packageinfo.ReproducibleBuild;
import io.github.yasmramos.forge.core.publish.PublishStaging;
import io.github.yasmramos.forge.core.resolver.AetherDependencyResolver;
import io.github.yasmramos.forge.core.sbom.SbomGenerator;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code forge publish} - assemble and stage the artifacts to publish (AGENT.md
 * section 6).
 *
 * <p>The command packages the project (if needed), then stages the POM, Gradle
 * Module Metadata, main/sources/javadoc JARs and, by default, the CycloneDX SBOM
 * under {@code target/forge-publish/} in Maven repository layout. Upload to the
 * target repository and GPG signing land in later increments; this command
 * produces and validates the exact artifact set they will send, so the
 * "generated POM must be valid for Maven Central without manual edits" promise
 * is verifiable offline.</p>
 */
public final class PublishCommand {

    /** Relative staging directory under the build base (Maven-style "target"). */
    public static final String STAGING_DIR = "forge-publish";

    private PublishCommand() {
    }

    public static int run(ForgeEngine engine, String[] args, PrintStream out) {
        if (engine == null) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "forge publish requires a project (no forge.toml found)");
        }
        ProjectConfig config = engine.getConfig();
        File projectRoot = engine.getProjectRoot();

        // Packaging must exist before staging; a missing JAR fails in the core
        // with FORGE-PUB-0004 rather than producing a half-empty staging dir.
        boolean skipBuild = hasFlag(args, "--skip-build");
        if (!skipBuild) {
            out.println("Packaging project before publish...");
            io.github.yasmramos.forge.core.ForgeEngine.BuildResult result = engine.build();
            if (!result.isSuccess()) {
                throw new ForgeException(ErrorCode.PUB_0004,
                    "build failed; fix the build before publishing");
            }
        }

        File buildDir = resolveBuildDir(config, projectRoot);
        File stagingDir = new File(buildDir, STAGING_DIR);

        Instant timestamp = Instant.ofEpochSecond(ReproducibleBuild.epochSeconds(
            projectRoot, config.getBuildSettings().getReproducibleTimestamp()));

        List<SbomGenerator.Component> components = config.getPublishSettings().isSbom()
            ? collectComponents(projectRoot) : Collections.emptyList();

        PublishStaging.Result staged =
            PublishStaging.stage(config, buildDir, stagingDir, components, timestamp);

        for (String warning : staged.getWarnings()) {
            out.println("  warning: " + warning);
        }
        out.println("Staged " + staged.getFiles().size() + " artifact(s) for publishing in "
            + staged.getDirectory().getAbsolutePath());
        for (File f : staged.getFiles()) {
            out.println("  " + f.getName());
        }
        out.println("Repository target: " + targetRepository(config)
            + " (upload arrives in a later increment)");
        return 0;
    }

    private static String targetRepository(ProjectConfig config) {
        ProjectConfig.PublishSettings pub = config.getPublishSettings();
        String key = isSnapshot(config.getVersion())
            ? pub.getSnapshotRepository() : pub.getRepository();
        return key == null || key.trim().isEmpty() ? "central" : key.trim();
    }

    private static boolean isSnapshot(String version) {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    private static List<SbomGenerator.Component> collectComponents(File projectRoot) {
        Map<String, Lockfile.LockedDependency> locked = readLockfile(projectRoot);
        List<SbomGenerator.Component> components = new ArrayList<>();
        try {
            AetherDependencyResolver.ResolvedGraph graph =
                DependencyCommand.resolveCompileGraph();
            for (AetherDependencyResolver.ResolvedArtifact a : graph.getArtifacts()) {
                String[] ga = splitCoordinates(a.getCoordinates());
                String sha = lockedSha(locked, ga[0] + ":" + ga[1]);
                if (sha == null) {
                    sha = SbomGenerator.sha256Of(a.getFile());
                }
                String scope = a.getScope() == null || a.getScope().trim().isEmpty()
                    ? "compile" : a.getScope();
                components.add(new SbomGenerator.Component(
                    ga[0], ga[1], ga[2], scope, sha));
            }
        } catch (ForgeException e) {
            // The SBOM is best-effort during publish: a resolution problem must
            // not block an artifact set that is otherwise complete. Surface it
            // as a component-less SBOM rather than failing the publish.
            return Collections.emptyList();
        }
        return components;
    }

    private static Map<String, Lockfile.LockedDependency> readLockfile(File projectRoot) {
        File lock = new File(projectRoot, Lockfile.FILE_NAME);
        if (!lock.isFile()) {
            return Collections.emptyMap();
        }
        try {
            return Lockfile.read(projectRoot).getDependencies();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static String lockedSha(Map<String, Lockfile.LockedDependency> locked, String ga) {
        Lockfile.LockedDependency d = locked.get(ga);
        return d == null ? null : d.sha256;
    }

    private static String[] splitCoordinates(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length >= 3) {
            return new String[] {parts[0], parts[1], parts[2]};
        }
        if (parts.length == 2) {
            return new String[] {parts[0], parts[1], ""};
        }
        return new String[] {"", coordinates, ""};
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** Build base directory: parent of the configured output directory. */
    private static File resolveBuildDir(ProjectConfig config, File projectRoot) {
        String configured = config.getOutputDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            Path classes = Paths.get(configured);
            // A relative output directory is relative to the project root, not
            // to the process working directory.
            if (!classes.isAbsolute()) {
                classes = projectRoot.toPath().resolve(classes);
            }
            Path parent = classes.normalize().getParent();
            return parent != null ? parent.toFile() : classes.toFile();
        }
        return new File(projectRoot, "target");
    }
}
