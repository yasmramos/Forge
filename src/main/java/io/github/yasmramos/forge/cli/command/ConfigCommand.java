package io.github.yasmramos.forge.cli.command;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;
import io.github.yasmramos.forge.core.multimodule.MultiModuleBuilder;
import io.github.yasmramos.forge.core.multimodule.MultiModuleProject;
import io.github.yasmramos.forge.core.multimodule.ModuleDescriptor;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code forge config} - inspect the configuration Forge actually resolved
 * (AGENT.md sections 8, 8bis, 10 and 15).
 *
 * <p>Configuration is the result of several layers: the base {@code forge.toml},
 * the selected variant overlay, and - in a multi-module project - inheritance
 * from the root. Guessing what survived those layers is how irreproducible
 * builds start, so this command prints the resolved values and, with
 * {@code --show-resolved <module>}, the fully merged per-module configuration
 * (section 8bis requires every layer to be visible this way).</p>
 */
public final class ConfigCommand {

    private ConfigCommand() {
    }

    /**
     * Runs {@code forge config [--show-resolved <module>]}.
     *
     * @param rootConfig the root configuration, already resolved with the
     *                   selected variant by the entry point
     * @param projectRoot the project root directory
     * @param variant    the selected variant, or null for the base configuration
     * @param args       command arguments after the {@code config} word
     * @param out        human-readable output stream
     * @return 0 on success
     * @throws ForgeException with a registered catalog code on any failure
     */
    public static int run(ProjectConfig rootConfig, File projectRoot, String variant,
                          String[] args, PrintStream out) {
        if (rootConfig == null) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "no forge.toml found; forge config needs a project configuration");
        }

        String showResolved = showResolvedTarget(args);
        if (showResolved != null) {
            return showResolvedModule(rootConfig, projectRoot, variant, showResolved, out);
        }

        renderResolvedConfig(rootConfig, variant, out);
        return 0;
    }

    /** {@code --show-resolved <module>}; null when the flag is absent. */
    private static String showResolvedTarget(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--show-resolved=")) {
                return blankToNull(arg.substring("--show-resolved=".length()));
            }
            if ("--show-resolved".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new ForgeException(ErrorCode.CFG_0002,
                        "usage: forge config --show-resolved <module>");
                }
                return blankToNull(args[i + 1]);
            }
        }
        return null;
    }

    private static void renderResolvedConfig(ProjectConfig config, String variant, PrintStream out) {
        out.println("Resolved configuration (forge.toml"
            + (variant != null ? " with variant '" + variant + "'" : ", base") + "):");
        out.println();
        out.println("[project]");
        out.println("  name     = " + quote(config.getName()));
        out.println("  version  = " + quote(config.getVersion()));
        out.println("  group    = " + quote(config.getGroup()));
        out.println("  packaging = " + quote(config.getPackaging()));
        if (variant != null) {
            out.println("  variant  = " + quote(variant));
        }
        if (!config.getVariantNames().isEmpty()) {
            out.println("  variants declared = " + config.getVariantNames());
        }
        out.println("  cache scope id    = " + quote(config.getCacheScopeId()));

        out.println();
        out.println("[toolchain]");
        out.println("  source = " + orDefault(config.getSourceCompatibility()));
        out.println("  target = " + orDefault(config.getTargetCompatibility()));
        out.println("  jdk    = " + orDefault(firstNonBlank(config.getJdkVersion(),
            config.getJdkVendor())));

        out.println();
        out.println("[build]");
        ProjectConfig.BuildSettings build = config.getBuildSettings();
        out.println("  incremental        = " + build.isIncremental());
        out.println("  parallel           = " + build.isParallel());
        out.println("  cache enabled      = " + build.isCacheEnabled());
        out.println("  threads            = " + build.getThreads());
        out.println("  encoding           = " + quote(build.getEncoding()));
        out.println("  debug              = " + build.isDebug());
        out.println("  resource filtering = " + build.isResourceFiltering());
        out.println("  reproducible       = " + orDefault(build.getReproducible()));
        if (notBlank(build.getRemoteCacheUrl())) {
            out.println("  remote cache       = " + build.getRemoteCacheUrl());
        }

        if (!config.getCompilerArgs().isEmpty()) {
            out.println();
            out.println("[compile]");
            out.println("  args       = " + config.getCompilerArgs());
            out.println("  processors = " + (config.getProcessors().isEmpty()
                ? "(auto-discovered from the classpath)" : config.getProcessors().toString()));
        }

        out.println();
        out.println("[dependencies]");
        renderDependencies(config, out);

        if (!config.getRepositories().isEmpty()) {
            out.println();
            out.println("[repositories]");
            for (Map.Entry<String, String> e : config.getRepositories().entrySet()) {
                out.println("  " + e.getKey() + " = " + e.getValue());
            }
        }

        if (!config.getModules().isEmpty()) {
            out.println();
            out.println("[project.modules]");
            for (Map.Entry<String, String> e : config.getModules().entrySet()) {
                out.println("  " + e.getKey() + " = " + e.getValue());
            }
            out.println();
            out.println("Run 'forge config --show-resolved <module>' to see a module's fully merged"
                + " configuration.");
        }
    }

    private static void renderDependencies(ProjectConfig config, PrintStream out) {
        Map<String, Object> deps = config.getDependencies();
        if (deps == null || deps.isEmpty()) {
            out.println("  (none declared in this file)");
            return;
        }
        List<String> coordinates = new ArrayList<>(deps.keySet());
        java.util.Collections.sort(coordinates);
        for (String coordinate : coordinates) {
            out.println("  " + coordinate + " = " + renderDependencyValue(deps.get(coordinate)));
        }
    }

    @SuppressWarnings("unchecked")
    private static String renderDependencyValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Object version = map.get("version");
            StringBuilder sb = new StringBuilder(quote(String.valueOf(version)));
            for (String key : new String[] {"type", "scope", "classifier"}) {
                if (map.get(key) != null) {
                    sb.append(" (").append(key).append('=').append(map.get(key)).append(')');
                }
            }
            return sb.toString();
        }
        return quote(String.valueOf(value));
    }

    /**
     * Prints one module's fully merged configuration (AGENT.md section 8bis):
     * inherited root values, the module's own base overrides and its
     * {@code [variant.<selected>]} overlay, all already applied.
     */
    private static int showResolvedModule(ProjectConfig rootConfig, File projectRoot, String variant,
                                          String moduleName, PrintStream out) {
        if (rootConfig.getModules() == null || rootConfig.getModules().isEmpty()) {
            throw new ForgeException(ErrorCode.CFG_0001,
                "this is not a multi-module project; [project.modules] is empty");
        }
        MultiModuleProject project = MultiModuleProject.discover(projectRoot, rootConfig, variant);
        ModuleDescriptor module = project.module(moduleName);
        if (module == null) {
            throw new ForgeException(ErrorCode.MMD_0003,
                "module '" + moduleName + "' is not declared in [project.modules]");
        }

        ProjectConfig resolved = MultiModuleBuilder.preparedConfig(
            module, new File(projectRoot, ".forge/workspace"),
            io.github.yasmramos.forge.core.multimodule.MultiModuleDependencies
                .moduleCoordinates(project));

        out.println("Resolved configuration for module '" + moduleName + "' ("
            + module.getCoordinate() + "):");
        if (variant != null) {
            out.println("variant '" + variant + "' "
                + (module.getConfig().getVariantNames().contains(variant)
                    ? "declared in this module" : "inherited from the root (not declared here)"));
        }
        out.println();
        renderResolvedConfig(resolved, variant, out);
        return 0;
    }

    private static String quote(String value) {
        return value == null ? "(unset)" : "\"" + value + "\"";
    }

    private static String orDefault(String value) {
        return notBlank(value) ? value : "(default)";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }
}
