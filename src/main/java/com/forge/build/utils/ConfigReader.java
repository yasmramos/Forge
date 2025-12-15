package com.forge.build.utils;

import com.forge.build.model.ProjectConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Configuration Reader for Forge Build System
 */
public class ConfigReader {
    
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public static ProjectConfig loadConfig(java.io.File configFile) throws IOException {
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject jsonConfig = JsonParser.parseReader(reader).getAsJsonObject();
            return parseConfig(jsonConfig);
        }
    }
    
    private static ProjectConfig parseConfig(JsonObject jsonConfig) {
        ProjectConfig config = new ProjectConfig();
        
        if (jsonConfig.has("name")) {
            config.setName(jsonConfig.get("name").getAsString());
        }
        
        if (jsonConfig.has("version")) {
            config.setVersion(jsonConfig.get("version").getAsString());
        }
        
        if (jsonConfig.has("sourceDirectory")) {
            config.setSourceDirectory(jsonConfig.get("sourceDirectory").getAsString());
        }
        
        if (jsonConfig.has("outputDirectory")) {
            config.setOutputDirectory(jsonConfig.get("outputDirectory").getAsString());
        }
        
        if (jsonConfig.has("sourcePaths")) {
            config.setSourcePaths(gson.fromJson(jsonConfig.get("sourcePaths"), List.class));
        }
        
        if (jsonConfig.has("dependencies")) {
            config.setDependencies(gson.fromJson(jsonConfig.get("dependencies"), Map.class));
        }
        
        if (jsonConfig.has("plugins")) {
            config.setPlugins(gson.fromJson(jsonConfig.get("plugins"), Map.class));
        }
        
        if (jsonConfig.has("buildSettings")) {
            JsonObject buildSettingsJson = jsonConfig.getAsJsonObject("buildSettings");
            ProjectConfig.BuildSettings buildSettings = parseBuildSettings(buildSettingsJson);
            config.setBuildSettings(buildSettings);
        }
        
        return config;
    }
    
    private static ProjectConfig.BuildSettings parseBuildSettings(JsonObject buildSettingsJson) {
        ProjectConfig.BuildSettings settings = new ProjectConfig.BuildSettings();
        
        if (buildSettingsJson.has("incremental")) {
            settings.setIncremental(buildSettingsJson.get("incremental").getAsBoolean());
        }
        
        if (buildSettingsJson.has("parallel")) {
            settings.setParallel(buildSettingsJson.get("parallel").getAsBoolean());
        }
        
        if (buildSettingsJson.has("cacheEnabled")) {
            settings.setCacheEnabled(buildSettingsJson.get("cacheEnabled").getAsBoolean());
        }
        
        if (buildSettingsJson.has("threads")) {
            settings.setThreads(buildSettingsJson.get("threads").getAsInt());
        }
        
        if (buildSettingsJson.has("compiler")) {
            settings.setCompiler(buildSettingsJson.get("compiler").getAsString());
        }
        
        if (buildSettingsJson.has("encoding")) {
            settings.setEncoding(buildSettingsJson.get("encoding").getAsString());
        }
        
        return settings;
    }
}