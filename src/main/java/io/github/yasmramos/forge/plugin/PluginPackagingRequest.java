package io.github.yasmramos.forge.plugin;

import java.io.File;

/**
 * Everything a {@link PluginPackagingType} needs to produce its artifact
 * (AGENT.md section 4). Forge hands the packaging type the compiled classes and
 * where to write the artifact; the type decides how to assemble it.
 */
public interface PluginPackagingRequest {

    /** The project being built. */
    PluginProject project();

    /** Compiled main classes, ready to be packaged. */
    File classesDirectory();

    /**
     * Directory the artifact must be written to. The artifact's name must match
     * {@link PluginPackagingType#artifactFileName()} so Forge can cache it.
     */
    File outputDirectory();
}
