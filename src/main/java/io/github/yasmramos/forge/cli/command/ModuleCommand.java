package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.multimodule.ModuleDescriptor;
import io.github.yasmramos.forge.core.multimodule.MultiModuleProject;

import java.io.PrintStream;
import java.util.List;

/**
 * {@code forge modules} - list the modules of a multi-module project
 * (AGENT.md section 5) in build order, with their coordinates and the
 * modules each one depends on.
 *
 * <p>Mirrors {@code forge tasks} for tasks: a cheap, read-only view of what
 * Forge discovered, so users can see the graph before building it.</p>
 */
public final class ModuleCommand {

    private ModuleCommand() {
    }

    public static int list(ForgeEngine engine, PrintStream out) {
        if (engine.getConfig().getModules().isEmpty()) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "no [project.modules] declared; this is not a multi-module project");
        }
        MultiModuleProject project = MultiModuleProject.discover(
            engine.getProjectRoot(), engine.getConfig(),
            engine.getConfig().getSelectedVariant());

        List<ModuleDescriptor> ordered = project.topologicalOrder();
        out.println("Modules (build order):");
        for (ModuleDescriptor m : ordered) {
            StringBuilder deps = new StringBuilder();
            for (String dep : project.dependenciesOf(m.getName())) {
                if (deps.length() > 0) {
                    deps.append(", ");
                }
                deps.append(dep);
            }
            out.println("  " + m.getName() + "  " + m.getCoordinate()
                + (deps.length() > 0 ? "  <- " + deps : ""));
        }
        return 0;
    }
}
