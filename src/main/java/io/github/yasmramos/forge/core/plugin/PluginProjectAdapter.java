package io.github.yasmramos.forge.core.plugin;

import io.github.yasmramos.forge.model.ProjectConfig;
import io.github.yasmramos.forge.plugin.PluginProject;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * Read-only {@link PluginProject} view over the loaded {@link ProjectConfig}
 * (AGENT.md section 4). Exposes only what a plugin task needs; the configuration
 * itself stays core-owned.
 */
final class PluginProjectAdapter implements PluginProject {

    private final ProjectConfig config;
    private final File root;

    PluginProjectAdapter(ProjectConfig config, File root) {
        this.config = config;
        this.root = root;
    }

    @Override
    public File root() {
        return root;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String version() {
        return config.getVersion();
    }

    @Override
    public File buildDirectory() {
        String configured = config.getOutputDirectory();
        Path base;
        if (configured != null && !configured.trim().isEmpty()) {
            Path classes = Paths.get(configured).toAbsolutePath().normalize();
            Path parent = classes.getParent();
            base = parent != null ? parent : classes;
        } else {
            base = new File(root, "target").toPath();
        }
        return base.toFile();
    }

    @Override
    public Map<String, String> properties() {
        Map<String, String> props = config.getProperties();
        return props == null ? Collections.emptyMap() : Collections.unmodifiableMap(props);
    }
}
