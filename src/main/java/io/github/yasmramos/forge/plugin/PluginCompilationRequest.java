package io.github.yasmramos.forge.plugin;

import java.io.File;
import java.util.List;

/**
 * Everything a {@link PluginCompiler} needs to compile one language's sources
 * (AGENT.md section 4). Forge gathers the sources and classpath; the compiler
 * decides how to turn them into classes.
 */
public interface PluginCompilationRequest {

    /** The project being built. */
    PluginProject project();

    /** Absolute source files whose extension this compiler declared. */
    List<File> sourceFiles();

    /**
     * Classpath entries already resolved for the compile scope (project
     * dependencies), as absolute paths. The compiler adds its own runtime jars
     * from its isolated classloader.
     */
    List<File> classpath();

    /** Directory the compiler must emit classes into. */
    File outputDirectory();
}
