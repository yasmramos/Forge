package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.importer.BuildModel;
import io.github.yasmramos.forge.core.importer.ForgeTomlWriter;
import io.github.yasmramos.forge.core.importer.GradleBuildImporter;
import io.github.yasmramos.forge.core.importer.MavenPomImporter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@code forge import} - generate a {@code forge.toml} from a {@code pom.xml} or
 * {@code build.gradle} (AGENT.md sections 10 and 14).
 *
 * <p>Migration must not be a big bang (section 14), so this command is additive
 * and safe: it never overwrites an existing {@code forge.toml}, it can print the
 * result with {@code --dry-run}, and it turns everything it cannot translate into
 * a visible TODO instead of guessing. It runs outside a project, because the
 * whole point is that no {@code forge.toml} exists yet.</p>
 */
public final class ImportCommand {

    private ImportCommand() {
    }

    /**
     * Runs {@code forge import [<build-file>] [--output=<path>] [--dry-run]}.
     *
     * @param args command arguments after the {@code import} word
     * @param out  human-readable output stream
     * @return 0 on success
     * @throws ForgeException with a registered catalog code on failure
     */
    public static int run(String[] args, PrintStream out) {
        return run(new File(System.getProperty("user.dir")), args, out);
    }

    /**
     * Runs the import against an explicit base directory. Kept separate from
     * {@link #run(String[], PrintStream)} so tests can target a temp directory
     * without mutating {@code user.dir}.
     *
     * @param projectDir directory the command treats as the project root
     * @param args       command arguments after the {@code import} word
     * @param out        human-readable output stream
     * @return 0 on success
     * @throws ForgeException with a registered catalog code on failure
     */
    public static int run(File projectDir, String[] args, PrintStream out) {
        File source = null;
        File output = null;
        boolean dryRun = false;

        for (String arg : args == null ? new String[0] : args) {
            if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if (arg.startsWith("--output=")) {
                output = resolve(projectDir, arg.substring("--output=".length()));
            } else if (arg.startsWith("-")) {
                throw new ForgeException(ErrorCode.CFG_0002,
                    "unknown option '" + arg + "'; usage: forge import [<build-file>]"
                        + " [--output=<path>] [--dry-run]");
            } else {
                source = resolve(projectDir, arg);
            }
        }

        if (source == null) {
            source = autoDetect(projectDir);
        }
        if (!source.isFile()) {
            throw new ForgeException(ErrorCode.IMP_0001,
                "no pom.xml or build.gradle found in " + projectDir.getAbsolutePath());
        }

        BuildModel model = importFrom(source);
        String toml = ForgeTomlWriter.write(model);

        if (dryRun) {
            out.println("# dry run: " + source.getName() + " -> (not written)");
            out.print(toml);
            return 0;
        }

        File target = output != null ? output : new File(projectDir, "forge.toml");
        if (target.exists()) {
            throw new ForgeException(ErrorCode.IMP_0003,
                target.getAbsolutePath() + " already exists");
        }
        write(target, toml);

        out.println("Imported " + source.getName() + " -> " + target.getPath());
        out.println("Project: " + model.getName() + " "
            + model.getVersion() + (model.getGroup() != null ? " (" + model.getGroup() + ")" : ""));
        out.println("Dependencies: " + model.getDependencies().size());
        if (!model.getTodos().isEmpty()) {
            out.println("Review needed: " + model.getTodos().size()
                + " item(s) marked TODO in the generated file.");
        }
        out.println("Next: run 'forge lock refresh' to resolve dependencies and write forge.lock.");
        return 0;
    }

    private static BuildModel importFrom(File source) {
        String name = source.getName().toLowerCase();
        if (name.equals("pom.xml")) {
            return MavenPomImporter.importPom(source);
        }
        if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
            return GradleBuildImporter.importBuild(source);
        }
        // An explicit path with an unexpected name: pick the importer by content
        // shape so `forge import path/to/thing.xml` still works.
        if (name.endsWith(".xml")) {
            return MavenPomImporter.importPom(source);
        }
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
            return GradleBuildImporter.importBuild(source);
        }
        throw new ForgeException(ErrorCode.IMP_0001,
            "unsupported build file '" + source.getName() + "'; expected pom.xml or build.gradle");
    }

    private static File autoDetect(File dir) {
        File pom = new File(dir, "pom.xml");
        if (pom.isFile()) {
            return pom;
        }
        File gradle = new File(dir, "build.gradle");
        if (gradle.isFile()) {
            return gradle;
        }
        return new File(dir, "build.gradle.kts");
    }

    private static File resolve(File base, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(base, path);
    }

    private static void write(File target, String content) {
        try {
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "could not write " + target.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
