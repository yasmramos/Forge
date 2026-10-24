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
    private final String[] args;
    private Scanner scanner;
    
    public ForgeCLI(String[] args) {
        this.args = args;
        this.scanner = new Scanner(System.in);
    }
    
    public void execute(ForgeEngine engine) {
        if (args.length == 0) {
            interactiveMode();
            return;
        }
        
        String command = args[0].toLowerCase();
        
        switch (command) {
            case "build":
                executeBuild(engine, args);
                break;
            case "clean":
                engine.clean();
                break;
            case "init":
                initializeProject();
                break;
            case "info":
                showInfo();
                break;
            case "lock":
                executeLock(args);
                break;
            case "help":
                showHelp();
                break;
            default:
                logger.warn("Unknown command: " + command);
                showHelp();
        }
    }

    /**
     * {@code forge lock refresh} - regenerate forge.lock explicitly.
     * Per AGENT.md section 7bis the lockfile is never rewritten by a build;
     * updating dependencies must be a conscious act.
     */
    private void executeLock(String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        if (!"refresh".equals(sub)) {
            System.err.println("error: unknown lock subcommand '" + sub + "'.");
            System.err.println("fix: run `forge lock refresh` to regenerate forge.lock.");
            return;
        }
        int exit = new io.github.yasmramos.forge.cli.command.LockCommand().refresh(System.out);
        if (exit != 0) {
            logger.warn("forge lock refresh failed with exit code " + exit);
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
    
    private void executeBuild(ForgeEngine engine, String[] args) {
        if (args.length > 1) {
            String buildType = args[1].toLowerCase();
            switch (buildType) {
                case "incremental":
                    System.out.println("Running incremental build...");
                    engine.buildIncremental();
                    break;
                case "full":
                    System.out.println("Running full build...");
                    engine.build();
                    break;
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
                        } else {
                            System.out.println("Task-graph build OK: " + report.cacheHits()
                                + " from cache, " + report.executed() + " executed.");
                        }
                    } catch (io.github.yasmramos.forge.cli.error.ForgeException fe) {
                        // Structured errors render their own what/why/how block
                        // (AGENT.md section 10bis); do not flatten them here.
                        System.err.println(fe.formatUserFacing());
                    } catch (Exception e) {
                        System.err.println("Task-graph build error: " + e.getMessage());
                        System.err.println("Fix: check forge.toml and run again with --debug"
                            + " for details.");
                    }
                    break;
                default:
                    logger.warn("Unknown build type: " + buildType);
                    showHelp();
            }
        } else {
            System.out.println("Running build...");
            engine.build();
        }
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
            System.err.println("Failed to initialize project: " + e.getMessage());
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
    }
}