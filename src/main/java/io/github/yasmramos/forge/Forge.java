package io.github.yasmramos.forge;

import io.github.yasmramos.forge.cli.ForgeCLI;
import io.github.yasmramos.forge.core.ForgeEngine;
import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.utils.ConfigReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Forge Build System - Main Entry Point
 * Ultra-fast Java Build System - Next Generation
 * 
 * @author Forge Team
 * @version 1.0.0
 */
public class Forge {
    
    private static final Logger logger = LoggerFactory.getLogger(Forge.class);
    private static ForgeEngine engine;
    private static ForgeCLI cli;
    
    public static void main(String[] args) {
        try {
            logger.info("Starting Forge Build System v1.0.0");
            
            // Initialize CLI
            cli = new ForgeCLI(args);
            
            // Load project configuration
            Optional<ProjectConfig> config = loadProjectConfig();
            
            if (config.isPresent()) {
                // Initialize engine with configuration
                engine = new ForgeEngine(config.get());
                
                // Execute command
                cli.execute(engine);
            } else {
                // Interactive mode if no project config found
                cli.interactiveMode();
            }
            
        } catch (Exception e) {
            logger.error("Failed to start Forge", e);
            System.exit(1);
        }
    }
    
    private static Optional<ProjectConfig> loadProjectConfig() {
        try {
            String currentDir = System.getProperty("user.dir");
            File configFile = Paths.get(currentDir, "forge.json").toFile();
            
            if (configFile.exists()) {
                logger.info("Loading Forge configuration from: " + configFile.getAbsolutePath());
                return Optional.of(ConfigReader.loadConfig(configFile));
            } else {
                logger.info("No forge.json found, running in standalone mode");
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.error("Failed to load project configuration", e);
            return Optional.empty();
        }
    }
}