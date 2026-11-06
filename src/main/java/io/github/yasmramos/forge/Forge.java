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
        int exitCode = 0;
        try {
            logger.info("Starting Forge Build System v1.0.0");

            // Initialize CLI (parses global flags: --format=json, --debug)
            cli = new ForgeCLI(args);

            // Load project configuration
            Optional<ProjectConfig> config = loadProjectConfig();

            // Interactive mode is entered only when no command was given.
            // With a command but no forge.toml, commands that do not need a
            // project (init, info, help, lock) still run; the rest fail with a
            // structured FORGE-CFG-0001 instead of dropping into a REPL.
            if (config.isPresent()) {
                engine = new ForgeEngine(config.get());
            }
            exitCode = cli.execute(engine);

        } catch (Exception e) {
            logger.error("Failed to start Forge", e);
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static Optional<ProjectConfig> loadProjectConfig() {
        try {
            String currentDir = System.getProperty("user.dir");
            File configFile = Paths.get(currentDir, ConfigReader.CONFIG_FILE_NAME).toFile();

            if (configFile.exists()) {
                logger.info("Loading Forge configuration from: " + configFile.getAbsolutePath());
                return Optional.of(ConfigReader.loadConfig(configFile));
            } else {
                logger.info("No forge.toml found, running in standalone mode");
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.error("Failed to load project configuration", e);
            return Optional.empty();
        }
    }
}