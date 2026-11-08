package io.github.yasmramos.forge.plugin;

import java.io.File;
import java.util.Map;

/**
 * Read-only view of the project a plugin task runs in (AGENT.md section 4).
 * Deliberately narrow: a plugin reads what it needs to act, but cannot mutate
 * the configuration or reach into core internals.
 */
public interface PluginProject {

    /** Directory holding {@code forge.toml} and {@code forge.lock}. */
    File root();

    /** Project name from {@code [project] name}. */
    String name();

    /** Project version from {@code [project] version}. */
    String version();

    /** Build output base directory (e.g. {@code target/}). */
    File buildDirectory();

    /** Declared project properties, for interpolation and diagnostics. */
    Map<String, String> properties();
}
