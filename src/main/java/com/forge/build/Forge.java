package com.forge.build;

import com.forge.build.cli.ForgeCLI;
import com.forge.build.core.ForgeEngine;
import com.forge.build.model.ProjectConfig;
import com.forge.build.utils.ConfigReader;
import com.forge.build.utils.Logger;
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
    
    private static final Logger logger = Logger.getLogger(Forge.class);
    private static ForgeEngine engine;
    private static ForgeCLI cli;
    
    public static void main(String[] args) {
        try {
            logger.info("🚀 Starting Forge Build System v1.0.0");
            
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
                logger.info("📄 Loading Forge configuration from: " + configFile.getAbsolutePath());
                return Optional.of(ConfigReader.loadConfig(configFile));
            } else {
                logger.info("ℹ️  No forge.json found, running in standalone mode");
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.error("Failed to load project configuration", e);
            return Optional.empty();
        }
    }
}