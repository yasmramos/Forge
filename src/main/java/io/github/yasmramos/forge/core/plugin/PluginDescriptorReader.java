package io.github.yasmramos.forge.core.plugin;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlParser;

import io.github.yasmramos.forge.cli.error.ErrorCode;
import io.github.yasmramos.forge.cli.error.ForgeException;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and validates a {@code forge-plugin.toml} descriptor (AGENT.md section
 * 4bis). Parsing errors fail fast with a structured {@code FORGE-PLG-*} error
 * answering what/why/how/docs (section 10bis), never a bare exception.
 */
public final class PluginDescriptorReader {

    public static final String DESCRIPTOR_FILE = "forge-plugin.toml";

    private static final List<String> REQUIRED_FIELDS =
        java.util.Arrays.asList("id", "name", "version", "api", "main");

    private PluginDescriptorReader() {
    }

    /** Reads and validates the descriptor at the given path. */
    public static PluginDescriptor read(File descriptorFile) {
        Config root;
        try {
            root = new TomlParser().parse(descriptorFile.toPath(), FileNotFoundAction.THROW_ERROR);
        } catch (Exception e) {
            throw new ForgeException(ErrorCode.PLG_0003,
                "cannot read " + descriptorFile + ": " + e.getMessage(), e);
        }
        return parse(root);
    }

    /** Parses and validates an already-loaded TOML tree. */
    static PluginDescriptor parse(Config root) {
        String schema = string(root, "schema");
        if (schema == null || schema.isEmpty()) {
            throw new ForgeException(ErrorCode.PLG_0003, "missing required field 'schema'");
        }
        if (!PluginDescriptor.SUPPORTED_SCHEMA.equals(schema)) {
            throw new ForgeException(ErrorCode.PLG_0002,
                "descriptor schema '" + schema + "', supported '" + PluginDescriptor.SUPPORTED_SCHEMA + "'");
        }

        UnmodifiableConfig plugin = root.get("plugin");
        if (plugin == null) {
            throw new ForgeException(ErrorCode.PLG_0003, "missing [plugin] section");
        }
        for (String field : REQUIRED_FIELDS) {
            String value = plugin.get(field);
            if (value == null || value.trim().isEmpty()) {
                throw new ForgeException(ErrorCode.PLG_0003, "missing required field 'plugin." + field + "'");
            }
        }

        String id = plugin.get("id");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new ForgeException(ErrorCode.PLG_0003,
                "'plugin.id' must be a reverse-DNS identifier, got '" + id + "'");
        }
        // The api range must be parseable before it can be checked for compatibility.
        try {
            ApiVersionRange.parse(plugin.<String>get("api"));
        } catch (IllegalArgumentException e) {
            throw new ForgeException(ErrorCode.PLG_0003,
                "invalid 'plugin.api' range '" + plugin.get("api") + "': " + e.getMessage(), e);
        }

        Map<String, String> dependencies = new LinkedHashMap<>();
        UnmodifiableConfig deps = root.get("dependencies");
        if (deps != null) {
            for (UnmodifiableConfig.Entry e : deps.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof String)) {
                    throw new ForgeException(ErrorCode.PLG_0003,
                        "dependency '" + e.getKey() + "' must map to a version string");
                }
                dependencies.put(e.getKey(), (String) v);
            }
        }

        List<PluginDescriptor.Provides> provides = new ArrayList<>();
        Object providesNode = root.get("provides");
        if (providesNode instanceof List) {
            for (Object item : (List<?>) providesNode) {
                if (!(item instanceof UnmodifiableConfig)) {
                    throw new ForgeException(ErrorCode.PLG_0003, "each [[provides]] entry must be a table");
                }
                UnmodifiableConfig p = (UnmodifiableConfig) item;
                String type = p.get("type");
                String name = p.get("name");
                if (type == null || name == null) {
                    throw new ForgeException(ErrorCode.PLG_0003,
                        "each [[provides]] entry needs 'type' and 'name'");
                }
                provides.add(new PluginDescriptor.Provides(type, name));
            }
        }

        Boolean reproducible = null;
        UnmodifiableConfig compatibility = root.get("compatibility");
        if (compatibility != null && compatibility.get("reproducible") != null) {
            Object r = compatibility.get("reproducible");
            if (!(r instanceof Boolean)) {
                throw new ForgeException(ErrorCode.PLG_0003,
                    "'compatibility.reproducible' must be a boolean");
            }
            reproducible = (Boolean) r;
        }

        return new PluginDescriptor(schema, id, plugin.get("name"), plugin.get("version"),
            plugin.get("api"), plugin.get("main"), dependencies, provides, reproducible);
    }

    /**
     * Verifies the descriptor's declared core API range accepts the running
     * Forge API version, or fails with {@code FORGE-PLG-0001}. Called before
     * loading so an incompatible plugin is never half-loaded (section 4bis).
     */
    public static void checkCompatibility(PluginDescriptor descriptor) {
        ApiVersionRange range;
        try {
            range = ApiVersionRange.parse(descriptor.getApi());
        } catch (IllegalArgumentException e) {
            throw new ForgeException(ErrorCode.PLG_0003,
                "invalid 'plugin.api' range '" + descriptor.getApi() + "': " + e.getMessage(), e);
        }
        if (!range.accepts(PluginApi.currentVersion())) {
            throw new ForgeException(ErrorCode.PLG_0001,
                "plugin '" + descriptor.idVersion() + "' requires api " + range
                    + " but this Forge implements " + PluginApi.CURRENT);
        }
    }

    private static String string(Config root, String key) {
        Object v = root.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
