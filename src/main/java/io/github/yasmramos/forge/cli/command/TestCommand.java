package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.core.multimodule.ModuleDescriptor;
import io.github.yasmramos.forge.core.multimodule.MultiModuleBuilder;
import io.github.yasmramos.forge.core.multimodule.MultiModuleDependencies;
import io.github.yasmramos.forge.core.multimodule.MultiModuleProject;
import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * {@code forge test} - run the tests without packaging (AGENT.md section 10).
 *
 * <p>It is the {@code test} half of a build, sharing the same compile and
 * {@link io.github.yasmramos.forge.core.test.TestExecutor} paths, so it can
 * never disagree with {@code forge build} about whether the tests pass. In a
 * multi-module project it runs the tests of every module, or of one module and
 * its dependencies when a module name is given.</p>
 */
public final class TestCommand {

    private TestCommand() {
    }

    public static int run(ForgeEngine engine, String[] args, PrintStream out) {
        if (!engine.getConfig().getModules().isEmpty()) {
            return runMultiModule(engine, args, out);
        }

        ForgeEngine.BuildResult result = engine.runTests();
        return report(result, out);
    }

    private static int runMultiModule(ForgeEngine engine, String[] args, PrintStream out) {
        String target = args.length > 0 ? args[0] : null;
        MultiModuleProject project = MultiModuleProject.discover(
            engine.getProjectRoot(), engine.getConfig(),
            engine.getConfig().getSelectedVariant());
        List<ModuleDescriptor> selected = target == null
            ? project.topologicalOrder()
            : project.partialOrder(target);

        // Sibling modules resolve each other from the shared workspace, exactly
        // as a multi-module build installs them (AGENT.md section 5).
        File workspaceDir = new File(engine.getProjectRoot(), ".forge/workspace");
        Set<String> coordinates = MultiModuleDependencies.moduleCoordinates(project);

        boolean allOk = true;
        for (ModuleDescriptor module : selected) {
            ProjectConfig prepared =
                MultiModuleBuilder.preparedConfig(module, workspaceDir, coordinates);
            ForgeEngine moduleEngine = new ForgeEngine(prepared, module.getDirectory());
            ForgeEngine.BuildResult result = moduleEngine.runTests();
            out.println("  [" + (result.isSuccess() ? "OK" : "FAIL") + "] " + module.getName());
            if (!result.isSuccess()) {
                allOk = false;
                if (result.getError() != null) {
                    throw result.getError();
                }
            }
        }
        return allOk ? 0 : 1;
    }

    private static int report(ForgeEngine.BuildResult result, PrintStream out) {
        if (result.getError() != null) {
            throw result.getError();
        }
        if (!result.isSuccess()) {
            out.println("Tests FAILED. See the report above for which test broke.");
            return 1;
        }
        // The exact counts come from the JUnit platform line above; do not
        // restate a ratio here, since the engine's counter tracks test sources.
        out.println("Tests passed.");
        return 0;
    }
}
