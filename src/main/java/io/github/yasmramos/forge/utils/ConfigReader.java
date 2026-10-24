package io.github.yasmramos.forge.utils;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import io.github.yasmramos.forge.model.ProjectConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration reader for the Forge Build System.
 *
 * <p>Parses {@code forge.toml} files using the NightConfig TOML library.
 * JSON configuration ({@code forge.json}) is no longer supported; all
 * project configuration must use the TOML schema (see {@code forge.toml}
 * at the repository root).</p>
 *
 * <p>Supported schema sections:</p>
 * <ul>
 *   <li>{@code forge} - schema version string.</li>
 *   <li>{@code [project]} - name, version and reusable properties.</li>
 *   <li>{@code [repositories]} - Maven-style repository map.</li>
 *   <li>{@code [toolchain]} - single source of truth for the Java version.</li>
 *   <li>{@code [source.main]} / {@code [source.test]} - source roots by scope.</li>
 *   <li>{@code [dependencies.compile|runtime|test|provided]} - scoped dependencies.</li>
 *   <li>{@code [build]} - incremental, parallel, threads, cache_enabled.</li>
 *   <li>{@code [compile]} - encoding, debug, extra compiler args.</li>
 *   <li>{@code [exec]} - main class, program args and JVM args.</li>
 * </ul>
 *
 * <p>String values support {@code ${name}} property interpolation against
 * {@code [project.properties]} plus the built-in keys {@code project.name},
 * {@code project.version} and {@code project.group}.</p>
 */
public class ConfigReader {

    /** Pattern matching {@code ${property}} placeholders inside string values. */
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** Default configuration file name used by Forge projects. */
    public static final String CONFIG_FILE_NAME = "forge.toml";

    /**
     * Loads and parses a {@code forge.toml} configuration file.
     *
     * @param configFile path to the TOML configuration file
     * @return the parsed project configuration
     * @throws IOException if the file cannot be read or parsed
     */
    public static ProjectConfig loadConfig(File configFile) throws IOException {
        TomlParser parser = new TomlParser();
        Config tomlConfig = parser.parse(configFile.toPath(), FileNotFoundAction.THROW_ERROR);
        return parseConfig(tomlConfig);
    }

    /**
     * Parses an already-loaded NightConfig {@link Config} tree into a
     * {@link ProjectConfig} model.
     *
     * @param root the root TOML configuration
     * @return the parsed project configuration
     */
    static ProjectConfig parseConfig(Config root) {
        ProjectConfig config = new ProjectConfig();

        // Top-level `forge` key holds the configuration schema version.
        config.setSchemaVersion(root.<String>get("forge"));

        // ------------------------------------------------------------------
        // [project] section: name, version, group and custom properties.
        // ------------------------------------------------------------------
        Map<String, String> properties = new LinkedHashMap<>();
        UnmodifiableConfig project = root.get("project");
        String projectName = project != null ? project.get("name") : null;
        String projectVersion = project != null ? project.get("version") : null;
        String projectGroup = project != null ? project.get("group") : null;

        config.setName(projectName);
        config.setVersion(projectVersion);
        config.setGroup(projectGroup);

        // Built-in properties available for ${...} interpolation.
        if (projectName != null) {
            properties.put("project.name", projectName);
        }
        if (projectVersion != null) {
            properties.put("project.version", projectVersion);
        }
        if (projectGroup != null) {
            properties.put("project.group", projectGroup);
        }

        // User-declared reusable properties from [project.properties].
        if (project != null) {
            UnmodifiableConfig declared = project.get("properties");
            if (declared != null) {
                for (UnmodifiableConfig.Entry entry : declared.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        properties.put(entry.getKey(), expand((String) value, properties));
                    }
                }
            }
        }
        config.setProperties(properties);

        config.setDescription(project != null ? expand(project.get("description"), properties) : null);

        // Module map from [project.modules]: name -> version expression.
        if (project != null) {
            UnmodifiableConfig declaredModules = project.get("modules");
            if (declaredModules != null) {
                Map<String, String> modulesMap = new LinkedHashMap<>();
                for (UnmodifiableConfig.Entry entry : declaredModules.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        modulesMap.put(entry.getKey(), expand((String) entry.getValue(), properties));
                    }
                }
                config.setModules(modulesMap);
            }
        }

        // ------------------------------------------------------------------
        // [toolchain] section: language level is the single source of truth
        // for source/target compatibility.
        // ------------------------------------------------------------------
        UnmodifiableConfig toolchain = root.get("toolchain");
        if (toolchain != null) {
            String language = expand(toolchain.get("language"), properties);
            if (language != null) {
                config.setSourceCompatibility(language);
                config.setTargetCompatibility(language);
            }
            config.setJdkVendor(expand(toolchain.get("jdk_vendor"), properties));
            config.setJdkVersion(expand(toolchain.get("jdk_version"), properties));
        }

        // ------------------------------------------------------------------
        // [source.main] / [source.test]: source roots separated by scope.
        // The legacy flat list keeps working for BuildAnalyzer.
        // ------------------------------------------------------------------
        List<String> mainJava = getStringList(root, "source", "main", "java", properties);
        List<String> mainResources = getStringList(root, "source", "main", "resources", properties);
        List<String> testJava = getStringList(root, "source", "test", "java", properties);
        List<String> testResources = getStringList(root, "source", "test", "resources", properties);

        config.setMainJavaSources(mainJava);
        config.setMainResourcePaths(mainResources);
        config.setTestJavaSources(testJava);
        config.setTestResourcePaths(testResources);

        List<String> sourcePaths = new ArrayList<>(mainJava);
        sourcePaths.addAll(mainResources);
        config.setSourcePaths(sourcePaths);

        if (!mainJava.isEmpty()) {
            config.setSourceDirectory(mainJava.get(0));
        }

        // ------------------------------------------------------------------
        // [dependencies.*]: flattened into the legacy dependency map, where
        // each coordinate maps to {version, type, scope}.
        // ------------------------------------------------------------------
        Map<String, Object> dependencies = new LinkedHashMap<>();
        for (String scope : new String[] {"compile", "runtime", "test", "provided"}) {
            UnmodifiableConfig depSection = getSection(root, "dependencies", scope);
            if (depSection == null) {
                continue;
            }
            for (UnmodifiableConfig.Entry entry : depSection.entrySet()) {
                Object raw = entry.getValue();
                Map<String, Object> depInfo = new LinkedHashMap<>();
                if (raw instanceof String) {
                    depInfo.put("version", expand((String) raw, properties));
                    depInfo.put("type", "maven");
                } else if (raw instanceof UnmodifiableConfig) {
                    UnmodifiableConfig table = (UnmodifiableConfig) raw;
                    depInfo.put("version", expand(table.<String>get("version"), properties));
                    Object type = table.get("type");
                    depInfo.put("type", type != null ? type : "maven");
                    if (table.contains("path")) {
                        depInfo.put("path", expand(table.<String>get("path"), properties));
                    }
                } else {
                    continue;
                }
                depInfo.put("scope", scope);
                dependencies.put(entry.getKey(), depInfo);
            }
        }
        config.setDependencies(dependencies);

        // ------------------------------------------------------------------
        // [repositories]
        // ------------------------------------------------------------------
        Map<String, String> repositories = new LinkedHashMap<>();
        UnmodifiableConfig repos = root.get("repositories");
        if (repos != null) {
            for (UnmodifiableConfig.Entry entry : repos.entrySet()) {
                if (entry.getValue() instanceof String) {
                    repositories.put(entry.getKey(), expand((String) entry.getValue(), properties));
                }
            }
        }
        config.setRepositories(repositories);

        // ------------------------------------------------------------------
        // [build] section (snake_case keys).
        // ------------------------------------------------------------------
        ProjectConfig.BuildSettings settings = config.getBuildSettings();
        UnmodifiableConfig build = root.get("build");
        if (build != null) {
            if (build.contains("incremental")) {
                settings.setIncremental(bool(build.get("incremental")));
            }
            if (build.contains("parallel")) {
                settings.setParallel(bool(build.get("parallel")));
            }
            if (build.contains("cache_enabled")) {
                settings.setCacheEnabled(bool(build.get("cache_enabled")));
            }
            if (build.contains("threads")) {
                int threads = number(build.get("threads"));
                // 0 means "auto": use the number of available cores.
                settings.setThreads(threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads);
            }
        }

        // ------------------------------------------------------------------
        // [compile] section: compiler options. source/target now live in
        // [toolchain], so they are not read here anymore.
        // ------------------------------------------------------------------
        UnmodifiableConfig compile = root.get("compile");
        if (compile != null) {
            if (compile.contains("encoding")) {
                settings.setEncoding(expand(compile.get("encoding"), properties));
            }
            if (compile.contains("debug")) {
                settings.setDebug(bool(compile.get("debug")));
            }
            config.setCompilerArgs(getStringList(root, "compile", "args", properties));
        }

        // ------------------------------------------------------------------
        // [exec] section: run configuration.
        // ------------------------------------------------------------------
        UnmodifiableConfig exec = root.get("exec");
        if (exec != null) {
            config.setMainClass(expand(exec.get("main"), properties));
            config.setExecArgs(getStringList(root, "exec", "args", properties));
            config.setJvmArgs(getStringList(root, "exec", "jvm_args", properties));
        }

        return config;
    }

    /**
     * Expands {@code ${name}} placeholders in a string value using the
     * property table. Unknown placeholders are left untouched. Supports
     * nested references through repeated passes (bounded to avoid cycles).
     */
    static String expand(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value;
        for (int pass = 0; pass < 10 && result.contains("${"); pass++) {
            Matcher matcher = PROPERTY_PATTERN.matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean replaced = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    replaced = true;
                } else {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(sb);
            result = sb.toString();
            if (!replaced) {
                break;
            }
        }
        return result;
    }

    /** Returns a nested table (sub-config) or {@code null} if absent. */
    private static UnmodifiableConfig getSection(Config root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof UnmodifiableConfig)) {
                return null;
            }
            current = ((UnmodifiableConfig) current).get(key);
        }
        return current instanceof UnmodifiableConfig ? (UnmodifiableConfig) current : null;
    }

    /** Reads a list-of-strings value at the given path, expanding properties. */
    private static List<String> getStringList(Config root, String firstKey, String secondKey,
                                              String thirdKey, Map<String, String> properties) {
        // Supports both 2-level (compile.args) and 3-level (source.main.java) paths.
        List<String> path = new ArrayList<>();
        path.add(firstKey);
        path.add(secondKey);
        if (thirdKey != null) {
            path.add(thirdKey);
        }
        Object node = root.get(path);
        List<String> result = new ArrayList<>();
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                if (item instanceof String) {
                    result.add(expand((String) item, properties));
                }
            }
        } else if (node instanceof String) {
            result.add(expand((String) node, properties));
        }
        return result;
    }

    /** Convenience overload for two-segment paths such as {@code compile.args}. */
    private static List<String> getStringList(Config root, String section, String key,
                                              Map<String, String> properties) {
        return getStringList(root, section, key, null, properties);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static int number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
