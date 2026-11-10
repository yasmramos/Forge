package io.github.yasmramos.forge.model;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Project Configuration Model for Forge Build System.
 *
 * <p>Populated from a {@code forge.toml} file (parsed with NightConfig).
 * The model keeps the legacy flat fields ({@code sourcePaths},
 * {@code dependencies}) for backward compatibility with the build engine,
 * and adds the TOML-schema fields: scoped sources, toolchain, repositories,
 * compile options and exec configuration.</p>
 */
public class ProjectConfig {
    private String schemaVersion;
    private String name;
    private String version;
    private String group;
    private String description;
    /**
     * Packaging type selected by the project (AGENT.md section 4: "register
     * packaging types"). Defaults to {@code "jar"}, the built-in type.
     */
    private String packaging = "jar";
    private String sourceDirectory;
    private String outputDirectory = "target/classes";
    private List<String> sourcePaths;
    private List<String> mainJavaSources = new ArrayList<>();
    private List<String> mainResourcePaths = new ArrayList<>();
    private List<String> testJavaSources = new ArrayList<>();
    private List<String> testResourcePaths = new ArrayList<>();
    private Map<String, String> properties = new LinkedHashMap<>();
    private Map<String, String> modules = new LinkedHashMap<>();
    /**
     * Optional per-module directory overrides from {@code [project.modules]}
     * (AGENT.md section 5): module name -> directory relative to the root.
     * When absent, the directory defaults to the module name's last segment
     * (e.g. {@code example.core} -> {@code core}).
     */
    private Map<String, String> moduleDirectories = new LinkedHashMap<>();
    private Map<String, String> repositories = new LinkedHashMap<>();
    /**
     * Directory of the multi-module workspace holding freshly built module
     * artifacts (AGENT.md section 5). Not read from TOML: it is set by the
     * multi-module builder so that resolving a module's configuration finds its
     * sibling modules by coordinate. Null for single-module projects.
     */
    private String workspaceDirectory;

    /**
     * {@code groupId:artifactId} coordinates of every module in the multi-module
     * project. Set together with {@link #workspaceDirectory} so the workspace
     * reader only answers for real modules and never shadows an unrelated
     * dependency that shares an artifactId and version (AGENT.md section 5).
     */
    private java.util.Set<String> workspaceModuleCoordinates = new java.util.LinkedHashSet<>();

    /**
     * Explicit version overrides declared in [resolutions] of forge.toml
     * (AGENT.md section 2bis): groupId:artifactId -> forced exact version.
     * Highest-precedence relief mechanism, above [constraints].
     */
    private Map<String, String> resolutions = new LinkedHashMap<>();

    /**
     * Forced versions declared in [constraints] of forge.toml (AGENT.md section
     * 2bis): groupId:artifactId -> exact version. Wins over everything
     * transitive, below [resolutions].
     */
    private Map<String, String> constraints = new LinkedHashMap<>();
    private String sourceCompatibility;
    private String targetCompatibility;
    private String jdkVendor;
    private String jdkVersion;
    private Map<String, Object> dependencies;
    private Map<String, Object> plugins;
    private List<String> compilerArgs = new ArrayList<>();
    /**
     * Names of every {@code [variant.<name>]} declared in this file (AGENT.md
     * section 8). Kept so orchestration can distinguish "unknown variant" (a
     * user error) from "variant not declared in this particular module" (a
     * notice that the module builds with its base configuration).
     */
    private java.util.Set<String> variantNames = new java.util.LinkedHashSet<>();
    /** Selected variant, or null when the base configuration is used (section 8). */
    private String selectedVariant;
    /**
     * Explicit annotation processors from {@code [compile].processors}
     * (AGENT.md section 23). Empty means the compiler discovers them from the
     * compile classpath via {@code META-INF/services}.
     */
    private List<String> processors = new ArrayList<>();
    private String mainClass;
    private List<String> execArgs = new ArrayList<>();
    private List<String> jvmArgs = new ArrayList<>();
    private BuildSettings buildSettings;
    /**
     * {@code [publish]} section (AGENT.md section 6): POM metadata, target
     * repositories and signing. Populated by {@code ConfigReader}.
     */
    private PublishSettings publishSettings = new PublishSettings();
    /**
     * {@code [test]} section (AGENT.md section 21): selective execution and
     * flaky-test retry. Populated by {@code ConfigReader}.
     */
    private TestSettings testSettings = new TestSettings();

    public ProjectConfig() {
        this.sourcePaths = new ArrayList<>();
        this.dependencies = new LinkedHashMap<>();
        this.plugins = new LinkedHashMap<>();
        this.buildSettings = new BuildSettings();
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = (packaging == null || packaging.trim().isEmpty()) ? "jar" : packaging;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties != null ? properties : new LinkedHashMap<>();
    }

    public Map<String, String> getModules() {
        return modules;
    }

    public void setModules(Map<String, String> modules) {
        this.modules = modules != null ? modules : new LinkedHashMap<>();
    }

    /**
     * Optional module directory overrides (module name -> relative directory).
     * Empty means "derive the directory from the module name's last segment".
     */
    public Map<String, String> getModuleDirectories() {
        return moduleDirectories;
    }

    public void setModuleDirectories(Map<String, String> moduleDirectories) {
        this.moduleDirectories = moduleDirectories != null ? moduleDirectories : new LinkedHashMap<>();
    }

    public Map<String, String> getRepositories() {
        return repositories;
    }

    public void setRepositories(Map<String, String> repositories) {
        this.repositories = repositories != null ? repositories : new LinkedHashMap<>();
    }

    /**
     * Multi-module workspace directory, or null for a single-module project.
     * Set programmatically by the multi-module builder; never read from TOML.
     */
    public String getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    public void setWorkspaceDirectory(String workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
    }

    /** Coordinates of the modules in the multi-module workspace. */
    public java.util.Set<String> getWorkspaceModuleCoordinates() {
        return workspaceModuleCoordinates;
    }

    public void setWorkspaceModuleCoordinates(java.util.Set<String> workspaceModuleCoordinates) {
        this.workspaceModuleCoordinates = workspaceModuleCoordinates != null
            ? workspaceModuleCoordinates : new java.util.LinkedHashSet<>();
    }

    public Map<String, String> getResolutions() {
        return resolutions;
    }

    public void setResolutions(Map<String, String> resolutions) {
        this.resolutions = resolutions != null ? resolutions : new LinkedHashMap<>();
    }

    /** [constraints]: GA -> exact version, below [resolutions] in precedence. */
    public Map<String, String> getConstraints() {
        return constraints;
    }

    public void setConstraints(Map<String, String> constraints) {
        this.constraints = constraints != null ? constraints : new LinkedHashMap<>();
    }

    public List<String> getMainJavaSources() {
        return mainJavaSources;
    }

    public void setMainJavaSources(List<String> mainJavaSources) {
        this.mainJavaSources = mainJavaSources != null ? mainJavaSources : new ArrayList<>();
    }

    public List<String> getMainResourcePaths() {
        return mainResourcePaths;
    }

    public void setMainResourcePaths(List<String> mainResourcePaths) {
        this.mainResourcePaths = mainResourcePaths != null ? mainResourcePaths : new ArrayList<>();
    }

    public List<String> getTestJavaSources() {
        return testJavaSources;
    }

    public void setTestJavaSources(List<String> testJavaSources) {
        this.testJavaSources = testJavaSources != null ? testJavaSources : new ArrayList<>();
    }

    public List<String> getTestResourcePaths() {
        return testResourcePaths;
    }

    public void setTestResourcePaths(List<String> testResourcePaths) {
        this.testResourcePaths = testResourcePaths != null ? testResourcePaths : new ArrayList<>();
    }

    public String getSourceCompatibility() {
        return sourceCompatibility;
    }

    public void setSourceCompatibility(String sourceCompatibility) {
        this.sourceCompatibility = sourceCompatibility;
    }

    public String getTargetCompatibility() {
        return targetCompatibility;
    }

    public void setTargetCompatibility(String targetCompatibility) {
        this.targetCompatibility = targetCompatibility;
    }

    public String getJdkVendor() {
        return jdkVendor;
    }

    public void setJdkVendor(String jdkVendor) {
        this.jdkVendor = jdkVendor;
    }

    public String getJdkVersion() {
        return jdkVersion;
    }

    public void setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    public List<String> getCompilerArgs() {
        return compilerArgs;
    }

    public void setCompilerArgs(List<String> compilerArgs) {
        this.compilerArgs = compilerArgs != null ? compilerArgs : new ArrayList<>();
    }

    /** Names of the variants declared by this configuration (section 8). */
    public java.util.Set<String> getVariantNames() {
        return variantNames;
    }

    public void setVariantNames(java.util.Set<String> variantNames) {
        this.variantNames = variantNames != null
            ? new java.util.LinkedHashSet<>(variantNames) : new java.util.LinkedHashSet<>();
    }

    /** Selected variant name, or null when the base configuration is used. */
    public String getSelectedVariant() {
        return selectedVariant;
    }

    public void setSelectedVariant(String selectedVariant) {
        this.selectedVariant = selectedVariant;
    }

    /**
     * Stable, machine-independent identity used to scope content-cache keys
     * (AGENT.md sections 3, 8bis, 17). It is the project's coordinate plus the
     * selected variant, so a build under one variant can never be served a
     * cached artifact produced under another: the variant may change compiler
     * arguments, toolchain and dependencies, all of which affect the output.
     * Uses logical names only, never paths, so the scope is portable.
     */
    public String getCacheScopeId() {
        String base = group + ":" + name;
        return selectedVariant != null ? base + "@" + selectedVariant : base;
    }

    /** Explicit annotation processors (section 23); empty means auto-discovery. */
    public List<String> getProcessors() {
        return processors;
    }

    public void setProcessors(List<String> processors) {
        this.processors = processors != null ? new ArrayList<>(processors) : new ArrayList<>();
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public List<String> getExecArgs() {
        return execArgs;
    }

    public void setExecArgs(List<String> execArgs) {
        this.execArgs = execArgs != null ? execArgs : new ArrayList<>();
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : new ArrayList<>();
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getSourceDirectory() {
        return sourceDirectory;
    }
    
    public void setSourceDirectory(String sourceDirectory) {
        this.sourceDirectory = sourceDirectory;
    }
    
    public String getOutputDirectory() {
        return outputDirectory;
    }
    
    public void setOutputDirectory(String outputDirectory) {
        // Relative paths are resolved against the current working directory and
        // stored as absolute, so build artifacts always land in the project's
        // own target/ directory even when the JVM CWD changes (e.g. Surefire
        // sets CWD to target/ during test runs). Absolute values are kept as-is.
        if (outputDirectory != null && !outputDirectory.trim().isEmpty()
                && !new java.io.File(outputDirectory).isAbsolute()) {
            this.outputDirectory = new java.io.File(outputDirectory).getAbsolutePath();
        } else {
            this.outputDirectory = outputDirectory;
        }
    }
    
    public List<String> getSourcePaths() {
        return sourcePaths;
    }
    
    public void setSourcePaths(List<String> sourcePaths) {
        this.sourcePaths = sourcePaths;
    }
    
    public Map<String, Object> getDependencies() {
        return dependencies;
    }
    
    public void setDependencies(Map<String, Object> dependencies) {
        this.dependencies = dependencies;
    }
    
    public Map<String, Object> getPlugins() {
        return plugins;
    }
    
    public void setPlugins(Map<String, Object> plugins) {
        this.plugins = plugins;
    }
    
    public BuildSettings getBuildSettings() {
        return buildSettings;
    }
    
    public void setBuildSettings(BuildSettings buildSettings) {
        this.buildSettings = buildSettings;
    }

    /** {@code [publish]} settings (AGENT.md section 6). */
    public PublishSettings getPublishSettings() {
        return publishSettings;
    }

    public void setPublishSettings(PublishSettings publishSettings) {
        this.publishSettings = publishSettings != null ? publishSettings : new PublishSettings();
    }

    /** {@code [test]} settings (AGENT.md section 21). */
    public TestSettings getTestSettings() {
        return testSettings;
    }

    public void setTestSettings(TestSettings testSettings) {
        this.testSettings = testSettings != null ? testSettings : new TestSettings();
    }

    /**
     * {@code [test]} section (AGENT.md section 21): selective execution and
     * flaky-test retry, both opt-in and off the default path.
     */
    public static class TestSettings {
        /**
         * {@code [test] selective}: run only the tests affected by a change,
         * derived from the source dependency graph. Off by default because the
         * approximation over-approximates and a first run has no baseline; when
         * off, every test runs, exactly as before.
         */
        private boolean selective = false;
        /**
         * {@code [test] retry_failed}: number of extra attempts for a failing
         * test class before it is reported as failed. 0 (default) means a
         * failure is final. Intended for known-flaky tests; a class that passes
         * on retry is reported as flaky, never silently as a pass.
         */
        private int retryFailed = 0;

        public boolean isSelective() {
            return selective;
        }

        public void setSelective(boolean selective) {
            this.selective = selective;
        }

        public int getRetryFailed() {
            return retryFailed;
        }

        public void setRetryFailed(int retryFailed) {
            this.retryFailed = Math.max(0, retryFailed);
        }
    }

    public static class BuildSettings {
        private boolean incremental = true;
        private boolean parallel = true;
        private boolean cacheEnabled = true;
        private int threads = Runtime.getRuntime().availableProcessors();
        private String compiler = "javac";
        private String encoding = "UTF-8";
        private boolean debug = true;
        /** [build] reproducible_timestamp: fixed artifact timestamp (ADR 17). */
        private String reproducibleTimestamp;
        /**
         * [build] remote_cache: base URL of the remote build cache (section 3),
         * or null for a local-only cache. Overridden by the FORGE_REMOTE_CACHE
         * environment variable, so CI can point at a cache without editing
         * forge.toml (section 24).
         */
        private String remoteCacheUrl;
        /**
         * [build] reproducible: {@code "warn"} (default), {@code "strict"} or
         * {@code "off"} (AGENT.md section 17). Plugins that declare
         * {@code reproducible = true} are verified by double-run sampling; a
         * mismatch warns in "warn" mode, fails the build in "strict" mode, and
         * is not checked at all in "off" mode.
         */
        private String reproducible = "warn";
        /**
         * [build] resource_filtering: opt-in {@code ${...}} substitution in
         * resources (AGENT.md section 22). Off by default: default-on filtering
         * is a bug source, since a binary file containing {@code ${something}}
         * would be corrupted. When on, values come from [project.properties]
         * plus the project name/version.
         */
        private boolean resourceFiltering = false;
        
        /**
         * [build] audit_oss_index: base URL of the OSS Index service used by
         * {@code forge audit} (AGENT.md section 18), or null for the public
         * endpoint. Overridable so a mirror or an air-gapped proxy can be used.
         */
        private String auditOssIndexUrl;
        /**
         * [build] audit_fail_on: lowest severity that makes {@code forge audit}
         * exit non-zero (AGENT.md section 18). One of {@code none} (never fail),
         * {@code low}, {@code medium}, {@code high} (default) or {@code critical}.
         */
        private String auditFailOn = "high";

        public boolean isIncremental() {
            return incremental;
        }
        
        public void setIncremental(boolean incremental) {
            this.incremental = incremental;
        }
        
        public boolean isParallel() {
            return parallel;
        }
        
        public void setParallel(boolean parallel) {
            this.parallel = parallel;
        }
        
        public boolean isCacheEnabled() {
            return cacheEnabled;
        }
        
        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }
        
        public int getThreads() {
            return threads;
        }
        
        public void setThreads(int threads) {
            this.threads = threads;
        }
        
        public String getCompiler() {
            return compiler;
        }
        
        public void setCompiler(String compiler) {
            this.compiler = compiler;
        }
        
        public String getEncoding() {
            return encoding;
        }
        
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
        
        public boolean isDebug() {
            return debug;
        }
        
        public void setDebug(boolean debug) {
            this.debug = debug;
        }

        /** [build] reproducible_timestamp; null when unset. */
        public String getReproducibleTimestamp() {
            return reproducibleTimestamp;
        }

        public void setReproducibleTimestamp(String reproducibleTimestamp) {
            this.reproducibleTimestamp = reproducibleTimestamp;
        }

        /** [build] remote_cache base URL, or null when unset. */
        public String getRemoteCacheUrl() {
            return remoteCacheUrl;
        }

        public void setRemoteCacheUrl(String remoteCacheUrl) {
            this.remoteCacheUrl = remoteCacheUrl;
        }

        /** [build] reproducible: {@code "warn"} (default) or {@code "strict"}. */
        public String getReproducible() {
            return reproducible;
        }

        public void setReproducible(String reproducible) {
            this.reproducible = reproducible;
        }

        /** True when opt-in resource filtering is requested (section 22). */
        public boolean isResourceFiltering() {
            return resourceFiltering;
        }

        /** [build] audit_oss_index base URL, or null for the public endpoint. */
        public String getAuditOssIndexUrl() {
            return auditOssIndexUrl;
        }

        public void setAuditOssIndexUrl(String auditOssIndexUrl) {
            this.auditOssIndexUrl = auditOssIndexUrl;
        }

        /** [build] audit_fail_on severity threshold (default {@code high}). */
        public String getAuditFailOn() {
            return auditFailOn;
        }

        public void setAuditFailOn(String auditFailOn) {
            this.auditFailOn = auditFailOn;
        }

        public void setResourceFiltering(boolean resourceFiltering) {
            this.resourceFiltering = resourceFiltering;
        }

        /** True when strict reproducibility is requested (section 17). */
        public boolean isReproducibleStrict() {
            return reproducible != null && "strict".equalsIgnoreCase(reproducible.trim());
        }

        /** True when reproducibility verification is disabled (section 17). */
        public boolean isReproducibleOff() {
            return reproducible != null && "off".equalsIgnoreCase(reproducible.trim());
        }
    }

    /**
     * {@code [publish]} section (AGENT.md section 6).
     *
     * <p>Publishing to Maven Central is non-negotiable, so Forge generates a
     * complete POM and Gradle Module Metadata automatically. Users only supply
     * the fields Maven Central requires (licenses, developers, SCM) plus the
     * target repositories and signing switch. Credentials never live here:
     * they belong in {@code ~/.forge/credentials.toml}.</p>
     */
    public static class PublishSettings {
        /** License identifier, e.g. {@code Apache-2.0} (SPDX) - required. */
        private String licenseName;
        /** License URL, e.g. {@code https://www.apache.org/licenses/LICENSE-2.0}. */
        private String licenseUrl;
        /** Comma-separated developer ids, aligned with developerNames/emails. */
        private String developerIds;
        private String developerNames;
        private String developerEmails;
        /** Project URL published in the POM. */
        private String url;
        /** SCM connection URL, e.g. {@code scm:git:https://github.com/me/repo.git}. */
        private String scmUrl;
        /**
         * Target repository key (into {@code [repositories]}) used by
         * {@code forge publish}. Defaults to {@code central}.
         */
        private String repository = "central";
        /** {@code [publish.snapshot]}: key of the snapshot repository. */
        private String snapshotRepository = "snapshots";
        /** When true, artifacts are GPG-signed before upload (section 6). */
        private boolean sign = true;
        /** When true, a CycloneDX SBOM is generated during publish (section 18). */
        private boolean sbom = true;

        public String getLicenseName() { return licenseName; }
        public void setLicenseName(String licenseName) { this.licenseName = licenseName; }

        public String getLicenseUrl() { return licenseUrl; }
        public void setLicenseUrl(String licenseUrl) { this.licenseUrl = licenseUrl; }

        public String getDeveloperIds() { return developerIds; }
        public void setDeveloperIds(String developerIds) { this.developerIds = developerIds; }

        public String getDeveloperNames() { return developerNames; }
        public void setDeveloperNames(String developerNames) { this.developerNames = developerNames; }

        public String getDeveloperEmails() { return developerEmails; }
        public void setDeveloperEmails(String developerEmails) { this.developerEmails = developerEmails; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getScmUrl() { return scmUrl; }
        public void setScmUrl(String scmUrl) { this.scmUrl = scmUrl; }

        public String getRepository() { return repository; }
        public void setRepository(String repository) { this.repository = repository; }

        public String getSnapshotRepository() { return snapshotRepository; }
        public void setSnapshotRepository(String snapshotRepository) {
            this.snapshotRepository = snapshotRepository;
        }

        public boolean isSign() { return sign; }
        public void setSign(boolean sign) { this.sign = sign; }

        public boolean isSbom() { return sbom; }
        public void setSbom(boolean sbom) { this.sbom = sbom; }
    }
}