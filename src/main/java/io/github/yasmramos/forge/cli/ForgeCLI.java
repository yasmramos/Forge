package io.github.yasmramos.forge.cli;

import io.github.yasmramos.forge.core.ForgeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Scanner;

/**
 * Command Line Interface for Forge Build System
 */
public class ForgeCLI {

    private final Logger logger = LoggerFactory.getLogger(ForgeCLI.class);
    private final CliOptions options;
    private final String[] args;
    private final io.github.yasmramos.forge.cli.error.ErrorRenderer errors;
    private Scanner scanner;

    public ForgeCLI(String[] args) {
        this.options = CliOptions.parse(args);
        this.args = options.restArray();
        this.errors = new io.github.yasmramos.forge.cli.error.ErrorRenderer(
            System.err, options.isJson(), options.isDebug());
        this.scanner = new Scanner(System.in);
    }

    /**
     * Executes the requested command and returns the process exit code
     * (AGENT.md section 10bis): 0 on success, 1 user/config error, 2 environment
     * error, 3 internal bug. Errors are rendered through ErrorRenderer so
     * --format=json and --debug apply uniformly.
     */
    public int execute(ForgeEngine engine) {
        if (args.length == 0) {
            interactiveMode();
            return 0;
        }

        String command = args[0].toLowerCase();

        try {
            switch (command) {
                case "build":
                    return executeBuild(requireEngine(engine), args);
                case "clean":
                    requireEngine(engine).clean();
                    return 0;
                case "init":
                    initializeProject();
                    return 0;
                case "info":
                    showInfo();
                    return 0;
                case "lock":
                    return executeLock(args);
                case "run":
                    return executeRun(requireEngine(engine), args);
                case "tree":
                    return io.github.yasmramos.forge.cli.command.DependencyCommand.tree(
                        tail(args), System.out);
                case "why":
                    return io.github.yasmramos.forge.cli.command.DependencyCommand.why(
                        tail(args), System.out);
                case "resolve":
                    return io.github.yasmramos.forge.cli.command.DependencyCommand.resolve(
                        tail(args), System.out);
                case "tasks":
                    return io.github.yasmramos.forge.cli.command.TaskCommand.list(System.out);
                case "help":
                    showHelp();
                    return 0;
                default:
                    throw new io.github.yasmramos.forge.cli.error.ForgeException(
                        io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0002,
                        "unknown command '" + command + "'");
            }
        } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
            return errors.render(fe);
        }
    }

    /**
     * Commands that operate on a project need a loaded configuration. When
     * Forge runs outside a project, fail with a structured, actionable error
     * (AGENT.md 10bis) rather than a null dereference.
     */
    private static ForgeEngine requireEngine(ForgeEngine engine) {
        if (engine == null) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0001,
                "this command needs a project; no forge.toml was found here");
        }
        return engine;
    }

    /** Arguments after the command word (i.e. dropping {@code args[0]}). */
    private static String[] tail(String[] args) {
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * {@code forge lock refresh} - regenerate forge.lock explicitly.
     * Per AGENT.md section 7bis the lockfile is never rewritten by a build;
     * updating dependencies must be a conscious act.
     */
    private int executeLock(String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        if (!"refresh".equals(sub)) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0002,
                "unknown lock subcommand '" + sub + "'; expected 'refresh'");
        }
        new io.github.yasmramos.forge.cli.command.LockCommand().refresh(System.out);
        return 0;
    }

    /**
     * {@code forge run [args...]} - launch the configured main class.
     * Per AGENT.md section 10 this is a command, not a graph task: the
     * executed program owns stdout (Forge logs go to stderr). Arguments
     * after "--" override the static [exec] args from forge.toml.
     */
    private int executeRun(ForgeEngine engine, String[] args) {
        java.util.List<String> userArgs = new java.util.ArrayList<>();
        boolean afterSeparator = false;
        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                afterSeparator = true;
                continue;
            }
            if (afterSeparator) {
                userArgs.add(args[i]);
            }
        }
        try {
            return io.github.yasmramos.forge.cli.command.RunCommand.run(engine, userArgs);
        } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
            throw fe;
        } catch (Exception e) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.RUN_0002, e.getMessage(), e);
        }
    }

    public void interactiveMode() {
        logger.info("Welcome to Forge Build System Interactive Mode");
        logger.info("Type 'help' for available commands or 'exit' to quit");

        while (true) {
            System.out.print("forge> ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                logger.info("Goodbye!");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            processInteractiveCommand(input);
        }
    }

    private void processInteractiveCommand(String input) {
        String[] parts = input.split("\\s+");
        String command = parts[0].toLowerCase();

        switch (command) {
            case "build":
                System.out.println("Building project...");
                // In a real implementation, you'd have the engine here
                break;
            case "clean":
                System.out.println("Cleaning project...");
                break;
            case "init":
                initializeProject();
                break;
            case "info":
                showInfo();
                break;
            case "help":
                showHelp();
                break;
            default:
                System.out.println("Unknown command. Type 'help' for available commands.");
        }
    }

    private int executeBuild(ForgeEngine engine, String[] args) {
        if (args.length > 1) {
            String buildType = args[1].toLowerCase();
            switch (buildType) {
                case "incremental":
                    System.out.println("Running incremental build...");
                    engine.buildIncremental();
                    return 0;
                case "full":
                    System.out.println("Running full build...");
                    return reportBuild(engine.build());
                case "graph":
                    // Experimental: task-graph + content-cache pipeline
                    // (AGENT.md decisions 1 and 3). Opt-in until it becomes default.
                    System.out.println("Running experimental task-graph build...");
                    try {
                        io.github.yasmramos.forge.core.graph.TaskScheduler.Report report =
                            engine.buildWithTaskGraph();
                        if (report.failed()) {
                            System.err.println("Task-graph build FAILED. See the per-task"
                                + " outcomes above for which task broke and why.");
                            return 1;
                        }
                        System.out.println("Task-graph build OK: " + report.cacheHits()
                            + " from cache, " + report.executed() + " executed.");
                        return 0;
                    } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
                        // Structured errors render their own what/why/how block
                        // (AGENT.md section 10bis); do not flatten them here.
                        throw fe;
                    } catch (Exception e) {
                        throw new io.github.yasmramos.forge.cli.error.ForgeException(
                            io.github.yasmramos.forge.cli.error.ErrorCode.INT_0001,
                            e.getMessage(), e);
                    }
                default:
                    throw new io.github.yasmramos.forge.cli.error.ForgeException(
                        io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0002,
                        "unknown build type '" + buildType + "'");
            }
        } else {
            System.out.println("Running build...");
            return reportBuild(engine.build());
        }
    }

    /**
     * Prints the outcome of a default/full build and returns the exit code.
     * The failure detail (including structured lockfile errors) is logged by
     * the engine; here we surface a non-zero-worthy signal and a pointer to
     * the fix instead of staying silent.
     */
    private int reportBuild(io.github.yasmramos.forge.core.ForgeEngine.BuildResult result) {
        if (result != null && !result.isSuccess()) {
            if (result.getError() != null) {
                // Render the structured failure the same way as any other error,
                // honouring --format=json/--debug (AGENT.md 10bis).
                return errors.render(result.getError());
            }
            System.err.println("Build FAILED. See the messages above for the cause"
                + " (run with --debug for a stack trace).");
            return 1;
        }
        return 0;
    }

    private void initializeProject() {
        System.out.println("Initializing new Forge project...");

        System.out.print("Project name: ");
        String name = scanner.nextLine().trim();

        System.out.print("Version (default: 1.0.0): ");
        String version = scanner.nextLine().trim();
        if (version.isEmpty()) {
            version = "1.0.0";
        }

        System.out.print("Source directory (default: src/main/java): ");
        String sourceDir = scanner.nextLine().trim();
        if (sourceDir.isEmpty()) {
            sourceDir = "src/main/java";
        }

        System.out.print("Output directory (default: target/classes): ");
        String outputDir = scanner.nextLine().trim();
        if (outputDir.isEmpty()) {
            outputDir = "target/classes";
        }

        // Create forge.toml configuration (TOML format, parsed with NightConfig)
        String config = String.format(
            "# Forge project configuration.\n" +
            "# Schema version of the Forge configuration format.\n" +
            "forge = \"0.1\"\n" +
            "\n" +
            "[project]\n" +
            "name    = \"%s\"\n" +
            "version = \"%s\"\n" +
            "\n" +
            "[repositories]\n" +
            "central = \"https://repo1.maven.org/maven2\"\n" +
            "\n" +
            "[toolchain]\n" +
            "language = \"11\"\n" +
            "\n" +
            "[source.main]\n" +
            "java      = [\"%s\"]\n" +
            "resources = [\"src/main/resources\"]\n" +
            "\n" +
            "[source.test]\n" +
            "java      = [\"src/test/java\"]\n" +
            "resources = [\"src/test/resources\"]\n" +
            "\n" +
            "[dependencies.compile]\n" +
            "\n" +
            "[dependencies.test]\n" +
            "\n" +
            "[build]\n" +
            "incremental   = true\n" +
            "parallel      = true\n" +
            "threads       = 0        # 0 = auto (available cores)\n" +
            "cache_enabled = true\n" +
            "\n" +
            "[compile]\n" +
            "encoding = \"UTF-8\"\n" +
            "debug    = true\n" +
            "args     = []\n" +
            "\n" +
            "[exec]\n" +
            "main     = \"\"\n" +
            "args     = []\n" +
            "jvm_args = []\n",
            name, version, sourceDir);

        try {
            java.nio.file.Files.write(
                java.nio.file.Paths.get("forge.toml"),
                config.getBytes()
            );
            System.out.println("Project initialized successfully!");
            System.out.println("Created forge.toml configuration file");
            System.out.println("Create your source files in: " + sourceDir);

        } catch (Exception e) {
            throw new io.github.yasmramos.forge.cli.error.ForgeException(
                io.github.yasmramos.forge.cli.error.ErrorCode.CFG_0001,
                "could not write forge.toml: " + e.getMessage(), e);
        }
    }

    private void showInfo() {
        System.out.println("Forge Build System Information:");
        System.out.println("  Version: 1.0.0-SNAPSHOT");
        System.out.println("  Java Version: " + System.getProperty("java.version"));
        System.out.println("  OS: " + System.getProperty("os.name"));
        System.out.println("  Available Processors: " + Runtime.getRuntime().availableProcessors());
    }

    private void showHelp() {
        System.out.println("Forge Build System Help");
        System.out.println();
        System.out.println("Available Commands:");
        System.out.println("  build [incremental|full|graph]  - Build the project");
        System.out.println("  lock refresh              - Regenerate forge.lock explicitly (never done by builds)");
        System.out.println("  tree [--depth=N] [filter] - Show the resolved dependency tree");
        System.out.println("  why <coordinate>          - Explain why a dependency is in the graph");
        System.out.println("  resolve [--explain]       - Inspect the dependency graph; --explain shows version derivation");
        System.out.println("  tasks                     - List available task types");
        System.out.println("  run [-- args...]          - Execute the [exec] main class (program owns stdout)");
        System.out.println("  clean                     - Clean build artifacts");
        System.out.println("  init                      - Initialize new project");
        System.out.println("  info                      - Show system information");
        System.out.println("  help                      - Show this help");
        System.out.println("  exit/quit                 - Exit interactive mode");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  forge build               - Run default build");
        System.out.println("  forge build incremental   - Run incremental build");
        System.out.println("  forge build graph         - Run experimental task-graph build (opt-in cache/parallelism)");
        System.out.println("  forge build full          - Run full build");
        System.out.println("  forge clean               - Clean project");
        System.out.println();
        System.out.println("Global flags (AGENT.md section 10bis):");
        System.out.println("  --format=json             - Emit structured errors as JSON (for CI)");
        System.out.println("  --debug                   - Append Java stack traces to errors");
        System.out.println();
        System.out.println("Exit codes: 0 success, 1 user/config error, 2 environment error, 3 internal bug");
        System.out.println();
    }
}