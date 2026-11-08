package io.github.yasmramos.forge.plugin;

import java.io.File;
import java.util.List;

/**
 * Everything a {@link PluginTestRunner} needs to run one language's tests
 * (AGENT.md section 4). Forge gathers the test sources and the classpath; the
 * runner decides how to compile and execute them.
 */
public interface PluginTestRunRequest {

    /** The project being built. */
    PluginProject project();

    /** Absolute test source files whose extension this runner declared. */
    List<File> sourceFiles();

    /**
     * Classpath entries already resolved for tests: the compiled main classes,
     * the project's test-scope dependencies, and the runner's own runtime jars
     * (from its isolated classloader).
     */
    List<File> classpath();

    /** Directory the runner may compile test classes into. */
    File outputDirectory();
}
