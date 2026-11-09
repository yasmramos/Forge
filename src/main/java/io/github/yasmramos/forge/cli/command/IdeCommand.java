package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.ide.IdeGenerators;
import io.github.yasmramos.forge.core.ide.IdeProjectBuilder;
import io.github.yasmramos.forge.core.ide.ProjectModel;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code forge ide} - export the project model to the IDEs (AGENT.md section 13,
 * phase 1).
 *
 * <p>This is the "export the configuration to the IDE" phase: Forge writes the
 * files IntelliJ ({@code .idea/} + {@code .iml}), Eclipse ({@code .project},
 * {@code .classpath}, {@code .settings/}) and VS Code ({@code .vscode/}) read,
 * derived from the same resolved configuration and classpath the build uses. It
 * is a one-way export - Forge never reads these files back - so a stale model is
 * refreshed by re-running the command, never by hand-editing.</p>
 *
 * <p>By default all three models are written. {@code --ide=<name>} narrows it to
 * one, and {@code --dry-run} prints what would be written without touching
 * disk.</p>
 */
public final class IdeCommand {

    private IdeCommand() {
    }

    public static int run(ForgeEngine engine, String[] args, PrintStream out) {
        if (engine == null) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "forge ide requires a project (no forge.toml found)");
        }
        String target = target(args);
        boolean dryRun = hasFlag(args, "--dry-run");

        ProjectModel model = IdeProjectBuilder.build(engine.getConfig(), engine.getProjectRoot());

        List<IdeGenerators.GeneratedFile> files = new ArrayList<>();
        if ("all".equals(target) || "intellij".equals(target)) {
            files.addAll(IdeGenerators.intellij(model));
        }
        if ("all".equals(target) || "eclipse".equals(target)) {
            files.addAll(IdeGenerators.eclipse(model));
        }
        if ("all".equals(target) || "vscode".equals(target)) {
            files.addAll(IdeGenerators.vscode(model));
        }

        File root = engine.getProjectRoot();
        if (dryRun) {
            out.println("forge ide --dry-run: " + files.size() + " file(s) would be written");
            for (IdeGenerators.GeneratedFile file : files) {
                out.println("  " + file.getPath());
            }
            return 0;
        }

        for (IdeGenerators.GeneratedFile file : files) {
            File destination = new File(root, file.getPath());
            try {
                File parent = destination.getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                Files.write(destination.toPath(), file.getContent().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new ForgeException(ErrorCode.IDE_0001,
                    "could not write " + file.getPath() + ": " + e.getMessage(), e);
            }
        }

        out.println("Exported " + files.size() + " IDE file(s) to " + root.getAbsolutePath());
        for (IdeGenerators.GeneratedFile file : files) {
            out.println("  " + file.getPath());
        }
        return 0;
    }

    private static String target(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--ide=")) {
                String value = arg.substring("--ide=".length()).trim().toLowerCase();
                if (!"all".equals(value) && !"intellij".equals(value)
                        && !"eclipse".equals(value) && !"vscode".equals(value)) {
                    throw new ForgeException(ErrorCode.IDE_0002,
                        "unknown IDE '" + value + "'; expected one of: intellij, eclipse, vscode, all");
                }
                return value;
            }
        }
        return "all";
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
