package io.github.yasmramos.forge.core.multimodule;

import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;

/**
 * A discovered module of a multi-module project (AGENT.md section 5).
 *
 * <p>A module is identified by its Maven coordinate ({@code group:artifact:version}),
 * because inter-module dependencies are declared by coordinate, never by path.
 * The {@link #getConfig() resolved configuration} already carries everything
 * inherited from the root (version, group, repositories, toolchain), so a module
 * behaves like a standalone project everywhere downstream.</p>
 */
public final class ModuleDescriptor {

    private final String name;
    private final String group;
    private final String version;
    private final File directory;
    private final ProjectConfig config;

    ModuleDescriptor(String name, String group, String version, File directory,
                     ProjectConfig config) {
        this.name = name;
        this.group = group;
        this.version = version;
        this.directory = directory;
        this.config = config;
    }

    /** Artifact id, e.g. {@code example.core}. */
    public String getName() {
        return name;
    }

    public String getGroup() {
        return group;
    }

    public String getVersion() {
        return version;
    }

    /** Module directory, holding its own {@code forge.toml}. */
    public File getDirectory() {
        return directory;
    }

    /** Configuration with root inheritance already applied. */
    public ProjectConfig getConfig() {
        return config;
    }

    /** {@code group:artifact}, the key inter-module dependencies refer to. */
    public String getGroupArtifact() {
        return group + ":" + name;
    }

    /** {@code group:artifact:version}, the module's published coordinate. */
    public String getCoordinate() {
        return group + ":" + name + ":" + version;
    }

    @Override
    public String toString() {
        return getCoordinate() + " (" + directory.getName() + ")";
    }
}
