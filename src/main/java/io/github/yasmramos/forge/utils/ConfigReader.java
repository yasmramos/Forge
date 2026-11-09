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
     * Loads a {@code forge.toml} seeding property expansion with inherited
     * properties (used for modules, which may reference {@code ${project.version}}
     * and other values inherited from the root, AGENT.md section 5).
     */
    public static ProjectConfig loadConfig(File configFile, Map<String, String> inheritedProperties)
            throws IOException {
        return loadConfig(configFile, inheritedProperties, null);
    }

    /**
     * Loads and parses a {@code forge.toml} with an explicit variant selection
     * (AGENT.md section 8). When {@code variant} is non-null and the file
     * declares {@code [variant.<variant>]}, the overlay is applied over the base
     * configuration before parsing; when the file does not declare it, the base
     * configuration is returned unchanged and the caller decides whether that is
     * an error (no module declares it) or a notice (some modules do).
     */
    public static ProjectConfig loadConfig(File configFile, Map<String, String> inheritedProperties,
                                           String variant) throws IOException {
        TomlParser parser = new TomlParser();
        Config tomlConfig = parser.parse(configFile.toPath(), FileNotFoundAction.THROW_ERROR);
        return parseConfig(tomlConfig, inheritedProperties, variant);
    }

    /**
     * Parses an already-loaded NightConfig {@link Config} tree into a
     * {@link ProjectConfig} model.
     *
     * @param root the root TOML configuration
     * @return the parsed project configuration
     */
    static ProjectConfig parseConfig(Config root) {
        return parseConfig(root, null);
    }

    /**
     * Parses a TOML tree, optionally seeding property expansion with values
     * inherited from a parent (root) configuration. Module {@code [project]}
     * values override the inherited ones.
     */
    static ProjectConfig parseConfig(Config root, Map<String, String> inheritedProperties) {
        return parseConfig(root, inheritedProperties, null);
    }

    /**
     * Parses a TOML tree with an optional variant overlay (AGENT.md section 8).
     *
     * <p>Application order is deterministic and documented: the selected
     * variant's {@code [variant.<name>]} table is merged over the base tree
     * <em>before</em> parsing, so every section (dependencies, toolchain,
     * compile args, build settings, repositories...) is overridden uniformly
     * with one rule: scalars and tables are replaced, and tables are merged
     * recursively so a variant may override one key of a section without
     * restating the rest. The variant name is recorded on the result so the
     * lockfile can fold it into {@code config_hash} (section 8bis).</p>
     */
    static ProjectConfig parseConfig(Config root, Map<String, String> inheritedProperties,
                                     String variant) {
        Config effective = root;
        if (variant != null && !variant.trim().isEmpty()) {
            UnmodifiableConfig overlay = getSection(root, "variant", variant);
            if (overlay != null) {
                effective = Config.copy(root);
                // Drop the whole [variant] tree from the effective config: it is
                // selection metadata, not project configuration.
                effective.remove("variant");
                mergeTables(effective, overlay);
            }
        }
        ProjectConfig config = parseConfigBase(effective, inheritedProperties);
        config.setVariantNames(declaredVariantNames(root));
        config.setSelectedVariant(variant);
        return config;
    }

    private static ProjectConfig parseConfigBase(Config root, Map<String, String> inheritedProperties) {
        ProjectConfig config = new ProjectConfig();

        // Top-level `forge` key holds the configuration schema version.
        config.setSchemaVersion(root.<String>get("forge"));

        // ------------------------------------------------------------------
        // [project] section: name, version, group and custom properties.
        // ------------------------------------------------------------------
        Map<String, String> properties = new LinkedHashMap<>();
        if (inheritedProperties != null) {
            properties.putAll(inheritedProperties);
        }
        UnmodifiableConfig project = root.get("project");
        String projectName = project != null ? project.get("name") : null;
        String projectVersion = project != null ? project.get("version") : null;
        String projectGroup = project != null ? project.get("group") : null;

        config.setName(projectName);
        config.setVersion(projectVersion);
        config.setGroup(projectGroup);

        // Built-in properties available for ${...} interpolation. A module that
        // omits version/group inherits the root's value through `properties`.
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
        config.setPackaging(project != null ? expand(project.get("packaging"), properties) : null);

        // Module map from [project.modules]: name -> version expression.
        // A module entry may also be a table to override the directory:
        //   "example.app" = { version = "${project.version}", dir = "app" }
        if (project != null) {
            UnmodifiableConfig declaredModules = project.get("modules");
            if (declaredModules != null) {
                Map<String, String> modulesMap = new LinkedHashMap<>();
                Map<String, String> dirsMap = new LinkedHashMap<>();
                for (UnmodifiableConfig.Entry entry : declaredModules.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        modulesMap.put(entry.getKey(), expand((String) value, properties));
                    } else if (value instanceof UnmodifiableConfig) {
                        UnmodifiableConfig table = (UnmodifiableConfig) value;
                        modulesMap.put(entry.getKey(), expand(table.get("version"), properties));
                        String dir = expand(table.get("dir"), properties);
                        if (dir != null && !dir.trim().isEmpty()) {
                            dirsMap.put(entry.getKey(), dir);
                        }
                    }
                }
                config.setModules(modulesMap);
                config.setModuleDirectories(dirsMap);
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
        // [plugins] (AGENT.md sections 4 and 4bis): plugins are declared with a
        // Maven coordinate and loaded explicitly. No autodiscovery: what is in
        // the file gets loaded, what is not, does not.
        // ------------------------------------------------------------------
        Map<String, Object> plugins = new LinkedHashMap<>();
        UnmodifiableConfig pluginSection = root.get("plugins");
        if (pluginSection != null) {
            for (UnmodifiableConfig.Entry entry : pluginSection.entrySet()) {
                Object raw = entry.getValue();
                if (raw instanceof String) {
                    plugins.put(entry.getKey(), expand((String) raw, properties));
                } else if (raw instanceof UnmodifiableConfig) {
                    UnmodifiableConfig table = (UnmodifiableConfig) raw;
                    plugins.put(entry.getKey(), expand(table.<String>get("version"), properties));
                }
            }
        }
        config.setPlugins(plugins);

        // ------------------------------------------------------------------
        // [resolutions] (AGENT.md section 2bis): groupId:artifactId -> forced
        // exact version. Highest-precedence conflict-alleviation mechanism.
        // ------------------------------------------------------------------
        Map<String, String> resolutions = new LinkedHashMap<>();
        UnmodifiableConfig resSection = root.get("resolutions");
        if (resSection != null) {
            for (UnmodifiableConfig.Entry entry : resSection.entrySet()) {
                if (entry.getValue() instanceof String) {
                    resolutions.put(entry.getKey(), expand((String) entry.getValue(), properties));
                }
            }
        }
        config.setResolutions(resolutions);

        // ------------------------------------------------------------------
        // [constraints] (AGENT.md section 2bis): groupId:artifactId -> forced
        // exact version. Wins over everything transitive, below [resolutions].
        // ------------------------------------------------------------------
        Map<String, String> constraints = new LinkedHashMap<>();
        UnmodifiableConfig constraintsSection = root.get("constraints");
        if (constraintsSection != null) {
            for (UnmodifiableConfig.Entry entry : constraintsSection.entrySet()) {
                if (entry.getValue() instanceof String) {
                    constraints.put(entry.getKey(), expand((String) entry.getValue(), properties));
                }
            }
        }
        config.setConstraints(constraints);

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
            if (build.contains("reproducible_timestamp")) {
                settings.setReproducibleTimestamp(
                    expand(build.get("reproducible_timestamp"), properties));
            }
            if (build.contains("remote_cache")) {
                settings.setRemoteCacheUrl(expand(build.get("remote_cache"), properties));
            }
            if (build.contains("reproducible")) {
                settings.setReproducible(expand(build.get("reproducible"), properties));
            }
            if (build.contains("resource_filtering")) {
                settings.setResourceFiltering(bool(build.get("resource_filtering")));
            }
            // [build] audit_oss_index / audit_fail_on drive `forge audit`
            // (AGENT.md section 18). The URL is also overridable by the
            // FORGE_OSS_INDEX environment variable (section 24).
            if (build.contains("audit_oss_index")) {
                settings.setAuditOssIndexUrl(expand(build.get("audit_oss_index"), properties));
            }
            if (build.contains("audit_fail_on")) {
                settings.setAuditFailOn(expand(build.get("audit_fail_on"), properties));
            }
        }

        String envOssIndex = System.getenv("FORGE_OSS_INDEX");
        if (envOssIndex != null && !envOssIndex.trim().isEmpty()) {
            settings.setAuditOssIndexUrl(envOssIndex.trim());
        }

        // FORGE_REMOTE_CACHE overrides the URL from forge.toml so CI can point
        // at a cache without editing the file (AGENT.md sections 3 and 24).
        String envRemoteCache = System.getenv("FORGE_REMOTE_CACHE");
        if (envRemoteCache != null && !envRemoteCache.trim().isEmpty()) {
            settings.setRemoteCacheUrl(envRemoteCache.trim());
        }

        // ------------------------------------------------------------------
        // [publish] section (AGENT.md section 6): POM metadata, target
        // repositories and signing. Credentials are deliberately absent here;
        // they live in ~/.forge/credentials.toml.
        // ------------------------------------------------------------------
        ProjectConfig.PublishSettings publish = config.getPublishSettings();
        UnmodifiableConfig publishSection = root.get("publish");
        if (publishSection != null) {
            publish.setLicenseName(expand(publishSection.get("license"), properties));
            publish.setLicenseUrl(expand(publishSection.get("license_url"), properties));
            publish.setDeveloperIds(expand(publishSection.get("developers"), properties));
            publish.setDeveloperNames(expand(publishSection.get("developer_names"), properties));
            publish.setDeveloperEmails(expand(publishSection.get("developer_emails"), properties));
            publish.setUrl(expand(publishSection.get("url"), properties));
            publish.setScmUrl(expand(publishSection.get("scm"), properties));
            if (publishSection.contains("repository")) {
                publish.setRepository(expand(publishSection.get("repository"), properties));
            }
            if (publishSection.contains("sign")) {
                publish.setSign(bool(publishSection.get("sign")));
            }
            if (publishSection.contains("sbom")) {
                publish.setSbom(bool(publishSection.get("sbom")));
            }
            UnmodifiableConfig snapshot = getSection(root, "publish", "snapshot");
            if (snapshot != null && snapshot.get("repository") != null) {
                publish.setSnapshotRepository(expand(snapshot.get("repository"), properties));
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
            // Annotation processors (section 23): optional explicit list; when
            // absent the compiler discovers them from the compile classpath.
            config.setProcessors(getStringList(root, "compile", "processors", properties));
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
     * Loads a module's {@code forge.toml} and applies inheritance from the root
     * configuration (AGENT.md section 5). A module inherits shared settings
     * (version, group, repositories, toolchain, properties) and may override
     * anything; module-only concerns (source roots, dependencies, exec) are
     * never inherited from the root.
     *
     * @param root       already-resolved root configuration
     * @param moduleToml the module's own forge.toml
     * @return the module configuration with root defaults filled in
     */
    public static ProjectConfig loadModuleConfig(ProjectConfig root, File moduleToml)
            throws IOException {
        return loadModuleConfig(root, moduleToml, null);
    }

    /**
     * Variant-aware module load (AGENT.md section 8bis). Application order is
     * fixed: the module inherits the root's <em>resolved-with-variant</em>
     * configuration, the module's own base values override it, and the module's
     * own {@code [variant.<selected>]} overlay (applied during parsing) wins over
     * everything. A module that does not declare the variant keeps its base
     * configuration; the caller reports that as a notice, never an error.
     */
    public static ProjectConfig loadModuleConfig(ProjectConfig root, File moduleToml, String variant)
            throws IOException {
        ProjectConfig module = loadConfig(moduleToml, root.getProperties(), variant);

        // Version and group: module wins; otherwise inherit from the root so the
        // module's coordinate matches what the root declares.
        if (isBlank(module.getVersion())) {
            module.setVersion(root.getVersion());
        }
        if (isBlank(module.getGroup())) {
            module.setGroup(root.getGroup());
        }
        if (isBlank(module.getSchemaVersion())) {
            module.setSchemaVersion(root.getSchemaVersion());
        }

        // Repositories: root first, module overrides on key collision.
        Map<String, String> repositories = new LinkedHashMap<>(root.getRepositories());
        repositories.putAll(module.getRepositories());
        module.setRepositories(repositories);

        // Toolchain: module value wins when set.
        if (isBlank(module.getSourceCompatibility())) {
            module.setSourceCompatibility(root.getSourceCompatibility());
        }
        if (isBlank(module.getTargetCompatibility())) {
            module.setTargetCompatibility(root.getTargetCompatibility());
        }
        if (isBlank(module.getJdkVendor())) {
            module.setJdkVendor(root.getJdkVendor());
        }
        if (isBlank(module.getJdkVersion())) {
            module.setJdkVersion(root.getJdkVersion());
        }

        // Properties: root first, module overrides. Re-expand so a module value
        // may reference an inherited property (e.g. ${project.version}).
        Map<String, String> properties = new LinkedHashMap<>(root.getProperties());
        for (Map.Entry<String, String> e : module.getProperties().entrySet()) {
            properties.put(e.getKey(), expand(e.getValue(), properties));
        }
        module.setProperties(properties);

        return module;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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

    /**
     * Recursively merges {@code overlay} into {@code target} (AGENT.md section
     * 8). Sub-tables are merged key by key so a variant overrides only what it
     * declares; anything else (scalars, arrays, lists) is replaced wholesale.
     * Lists are replaced rather than appended, matching section 8's
     * "predictability wins" rule.
     */
    private static void mergeTables(Config target, UnmodifiableConfig overlay) {
        for (UnmodifiableConfig.Entry entry : overlay.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object existing = target.get(key);
            if (value instanceof UnmodifiableConfig) {
                if (existing instanceof Config) {
                    mergeTables((Config) existing, (UnmodifiableConfig) value);
                } else {
                    target.set(key, Config.copy((UnmodifiableConfig) value));
                }
            } else {
                target.set(key, value);
            }
        }
    }

    /**
     * Names of every {@code [variant.<name>]} table declared in a config, sorted
     * for deterministic reporting (AGENT.md section 8bis: the notice listing the
     * modules that ignored a variant must not depend on file order).
     */
    private static java.util.Set<String> declaredVariantNames(Config root) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        UnmodifiableConfig variants = getSection(root, "variant");
        if (variants != null) {
            for (UnmodifiableConfig.Entry entry : variants.entrySet()) {
                if (entry.getValue() instanceof UnmodifiableConfig) {
                    names.add(entry.getKey());
                }
            }
        }
        return names;
    }
}
