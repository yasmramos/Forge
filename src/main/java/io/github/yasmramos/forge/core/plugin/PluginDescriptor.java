package io.github.yasmramos.forge.core.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The parsed {@code forge-plugin.toml} descriptor (AGENT.md section 4bis).
 * This TOML is part of Forge's public API: it is versioned by {@code schema}
 * and validated like any other public surface. The descriptor is data only;
 * actual registration happens in code through the plugin entry point, and any
 * drift between the two is reported rather than silently accepted.
 */
public final class PluginDescriptor {

    /** Schema version of the plugin descriptor this core understands. */
    public static final String SUPPORTED_SCHEMA = "0.1";

    /** One {@code [[provides]]} entry: a declarative summary for diagnostics. */
    public static final class Provides {
        private final String type;
        private final String name;

        public Provides(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() { return type; }
        public String getName() { return name; }

        @Override
        public String toString() {
            return type + ":" + name;
        }
    }

    private final String schema;
    private final String id;
    private final String name;
    private final String version;
    private final String api;
    private final String main;
    private final java.util.Map<String, String> dependencies;
    private final List<Provides> provides;
    private final Boolean reproducible;

    public PluginDescriptor(String schema, String id, String name, String version,
                            String api, String main,
                            java.util.Map<String, String> dependencies,
                            List<Provides> provides, Boolean reproducible) {
        this.schema = schema;
        this.id = id;
        this.name = name;
        this.version = version;
        this.api = api;
        this.main = main;
        this.dependencies = dependencies == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(dependencies));
        this.provides = provides == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(provides));
        this.reproducible = reproducible;
    }

    public String getSchema() { return schema; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getApi() { return api; }
    public String getMain() { return main; }
    public java.util.Map<String, String> getDependencies() { return dependencies; }
    public List<Provides> getProvides() { return provides; }

    /** Declared reproducibility, or {@code null} when the plugin does not say. */
    public Boolean getReproducible() { return reproducible; }

    /** Identity used in cache keys and diagnostics: {@code id:version}. */
    public String idVersion() {
        return id + ":" + version;
    }
}
