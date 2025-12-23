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
            case "help":
                showHelp();
                break;
            default:
                logger.warn("Unknown command: " + command);
                showHelp();
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
        
        // Create forge.json configuration
        String config = String.format("{\n" +
            "  \"name\": \"%s\",\n" +
            "  \"version\": \"%s\",\n" +
            "  \"sourceDirectory\": \"%s\",\n" +
            "  \"outputDirectory\": \"%s\",\n" +
            "  \"sourcePaths\": [\"src/main/java\"],\n" +
            "  \"dependencies\": {},\n" +
            "  \"plugins\": {},\n" +
            "  \"buildSettings\": {\n" +
            "    \"incremental\": true,\n" +
            "    \"parallel\": true,\n" +
            "    \"cacheEnabled\": true,\n" +
            "    \"threads\": %d,\n" +
            "    \"compiler\": \"javac\",\n" +
            "    \"encoding\": \"UTF-8\"\n" +
            "  }\n" +
            "}", name, version, sourceDir, outputDir, 
            Runtime.getRuntime().availableProcessors());
        
        try {
            java.nio.file.Files.write(
                java.nio.file.Paths.get("forge.json"), 
                config.getBytes()
            );
            System.out.println("Project initialized successfully!");
            System.out.println("Created forge.json configuration file");
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
        System.out.println("  build [incremental|full]  - Build the project");
        System.out.println("  clean                     - Clean build artifacts");
        System.out.println("  init                      - Initialize new project");
        System.out.println("  info                      - Show system information");
        System.out.println("  help                      - Show this help");
        System.out.println("  exit/quit                 - Exit interactive mode");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  forge build               - Run default build");
        System.out.println("  forge build incremental   - Run incremental build");
        System.out.println("  forge build full          - Run full build");
        System.out.println("  forge clean               - Clean project");
        System.out.println();
    }
}