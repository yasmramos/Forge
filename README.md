# Forge Build System

Forge is a lightweight, modular Java build system designed for modern software development. It provides a flexible and extensible framework for building, testing, and packaging Java applications with support for incremental compilation, dependency management, and build caching.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Usage](#usage)
- [Modules](#modules)
- [Building and Testing](#building-and-testing)
- [Contributing](#contributing)
- [License](#license)

## Features

Forge provides a comprehensive set of features for Java project management:

- **Incremental Compilation**: Only recompiles files that have changed, significantly reducing build times for large projects.
- **Dependency Management**: Automatically resolves and manages project dependencies with flexible configuration options.
- **Build Caching**: Implements intelligent caching mechanisms to avoid redundant work across builds.
- **Multi-threaded Builds**: Utilizes parallel processing for faster compilation of large codebases.
- **Modular Architecture**: Clean separation of concerns with specialized modules for different build tasks.
- **CLI Interface**: Command-line interface for easy integration into development workflows.
- **Project Analysis**: Analyzes project structure and calculates complexity metrics for better insights.

## Architecture

Forge follows a modular architecture with clear separation of responsibilities:

```
Forge/
├── src/main/java/io/github/yasmramos/forge/
│   ├── Forge.java                    # Main entry point
│   ├── cache/                        # Build caching functionality
│   │   └── ForgeCache.java
│   ├── core/                         # Core build engine components
│   │   ├── ForgeEngine.java          # Main build orchestration
│   │   ├── BuildAnalyzer.java        # Project structure analysis
│   │   ├── Compiler.java             # Source compilation
│   │   └── resolver/                 # Maven Resolver, conflict policy, lockfile
│   ├── cli/                          # Command-line interface
│   │   └── ForgeCLI.java
│   ├── model/                        # Data models and configurations
│   └── utils/                        # Utility functions (ConfigReader)
└── src/test/java/                    # Test suite
```

## Prerequisites

Before installing Forge, ensure you have the following prerequisites:

- **Java Development Kit (JDK) 11 or higher**: Forge requires Java 11 or later to run.
- **Maven 3.6 or higher**: Required for building and managing Forge's own dependencies.
- **Git**: Recommended for version control integration.

## Installation

To install Forge, follow these steps:

1. Clone the repository to your local machine:

```bash
git clone https://github.com/yasmramos/Forge.git
cd Forge
```

2. Build the project using Maven:

```bash
mvn clean install
```

3. Verify the installation by running the test suite:

```bash
mvn test
```

All tests should pass successfully, confirming the installation is complete.

## Quick Start

The fastest way to get started with Forge is to use the command-line interface:

```bash
# Build the current project
java -jar target/forge-1.0.0-SNAPSHOT.jar build

# Clean build artifacts
java -jar target/forge-1.0.0-SNAPSHOT.jar clean

# Regenerate forge.lock
java -jar target/forge-1.0.0-SNAPSHOT.jar lock refresh
```

For a new project, run `forge init` to scaffold a `forge.toml` file in your project root, or create it manually:

```toml
forge = "0.1"

[project]
name    = "my-project"
version = "1.0.0"

[source.main]
java      = ["src/main/java"]
resources = ["src/main/resources"]
```

## Configuration

Forge supports configuration through TOML files (`forge.toml`) and programmatic APIs. The configuration system is flexible and allows customization of all aspects of the build process. TOML parsing is provided by the [NightConfig](https://github.com/MineCreepers/NightConfig) library. See [`forge.toml`](forge.toml) at the repository root for a complete reference.

> **Note:** JSON configuration (`forge.json`) has been fully migrated to TOML. Forge no longer reads JSON configuration files.

### Configuration File Format

Configuration files use TOML format with the following sections:

| Section | Description |
| --- | --- |
| `forge` | Configuration schema version (currently `"0.1"`) |
| `[project]` | Project identity: `name`, `version`, optional `group` / `description` |
| `[project.properties]` | Reusable properties expanded as `${name}` inside string values |
| `[project.modules]` | Multi-module map: module name to version expression |
| `[repositories]` | Named Maven repositories (e.g. `central = "https://repo1.maven.org/maven2"`) |
| `[toolchain]` | Single source of truth for the Java version: `language`, optional `jdk_vendor` / `jdk_version` |
| `[source.main]` / `[source.test]` | Source roots per scope: `java` and `resources` arrays |
| `[dependencies.compile\|runtime\|test\|provided]` | Scoped dependencies: `"group:artifact" = "version"` |
| `[constraints]` | Forced versions: `"group:artifact" = "version"`; win over transitive, below `[resolutions]` |
| `[resolutions]` | Per-coordinate pins that win over everything: `"group:artifact" = "version"` |
| `[build]` | Build behavior options (see below) |
| `[compile]` | Compiler options: `encoding`, `debug`, `args`, `processors` |
| `[exec]` | Run configuration: `main`, `args`, `jvm_args` |
| `[publish]` | Release metadata and target repository for `forge publish` (see below) |

Supported `[build]` keys (snake_case):

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| `incremental` | boolean | `true` | Only recompile changed sources |
| `parallel` | boolean | `true` | Use parallel processing where applicable |
| `cache_enabled` | boolean | `true` | Enable the on-disk build cache (`.forge-cache`) |
| `threads` | number | `0` (auto) | Worker thread count; `0` means available CPUs |
| `remote_cache` | string | unset | Base URL of a remote build cache; also settable via `FORGE_REMOTE_CACHE` |
| `reproducible` | string | `warn` | Reproducibility policy for plugins: `warn`, `strict`, or `off` |
| `resource_filtering` | boolean | `false` | Opt-in `${...}` substitution in resources; off by default so binary files are never corrupted |

Resources declared under `[source.main].resources` (or the conventional
`src/main/resources`) are copied into the compiled-classes directory and thus
into the packaged jar. Copying is content-based: an unchanged resource is never
rewritten. Filtering is **opt-in** — enable `resource_filtering` to expand
`${...}` placeholders using `[project.properties]` plus `project.name`,
`project.version` and `project.group`.

#### Annotation processors

Forge supports annotation processors (AGENT.md section 23). By default they are
discovered from the compile classpath: any resolved jar or directory carrying
`META-INF/services/javax.annotation.processing.Processor` declares its
processors, and declaring the dependency (Lombok, MapStruct, Dagger) is already
the intent to run them. Processors run with a dedicated `-processorpath`, kept
separate from the compile classpath. When the classpath declares none, Forge
compiles with `-proc:none`, so unrelated libraries never trigger accidental
discovery.

To run a processor that is *not* auto-declared, list it explicitly:

```toml
[compile]
processors = ["com.example.MyProcessor"]
```

#### Remote build cache

Set `remote_cache` (or the `FORGE_REMOTE_CACHE` env var) to a base URL and Forge
mirrors the local task cache there, so CI runners and machines reuse each
other's work. The protocol is deliberately small, so anyone can run a server:

- `GET <base>/<hash>` — `200` with the entry bytes on a hit, `404` on a miss.
- `PUT <base>/<hash>` with the entry bytes — `2xx` stores it.

Entries are content-addressed by the task hash (which already covers every
input, toolchain, Forge and plugin version) and immutable: a `PUT` for an
existing hash is a no-op, so concurrent uploads of identical content are always
safe. A miss or an outage degrades to a local build; it never fails the build.

#### Reproducibility policy

Reproducibility is guaranteed for core tasks; plugins are best-effort (§17). A
plugin declares `[compatibility] reproducible = true` in its `forge-plugin.toml`,
and Forge does not take that claim on faith: it runs each of that plugin's tasks
a second time and compares the output trees by content hash. A mismatch reports
`FORGE-RPR-0001` naming the plugin and task.

`[build] reproducible` selects the policy:

| Value | Behavior on a mismatch |
| --- | --- |
| `warn` (default) | Log the mismatch loudly; the build succeeds |
| `strict` | Fail the build with `FORGE-RPR-0001` |
| `off` | Skip the check entirely (no second run) |

Verification doubles the cost of the checked tasks, so a project that does not
need the guarantee can set `off`.

#### Publishing metadata

`forge publish` stages everything a release needs and validates it against the
fields Maven Central requires. Those fields live under `[publish]`:

| Key | Description |
| --- | --- |
| `license` | SPDX license id (e.g. `Apache-2.0`) — required |
| `license_url` | License URL |
| `developers` | Comma-separated developer ids |
| `developer_names` | Comma-separated developer names |
| `developer_emails` | Comma-separated developer emails |
| `url` | Project URL — required |
| `scm` | SCM connection, e.g. `scm:git:https://github.com/me/repo.git` — required |
| `repository` | Target repository key from `[repositories]` (default `central`) |
| `sign` | GPG-sign artifacts before upload (default `true`) |
| `sbom` | Generate a CycloneDX SBOM during publish (default `true`) |

A release snapshot is routed to `[publish.snapshot] repository` instead of the
release repository when the version ends in `-SNAPSHOT`. Credentials are never
read from `forge.toml`; they belong in `~/.forge/credentials.toml`.

```toml
[publish]
license = "Apache-2.0"
license_url = "https://www.apache.org/licenses/LICENSE-2.0"
developers = "jdoe"
developer_names = "Jane Doe"
developer_emails = "jane@example.com"
url = "https://example.io/awesome"
scm = "scm:git:https://github.com/example/awesome.git"

[publish.snapshot]
repository = "ossrh-snapshots"
```

Missing required metadata fails at configure time with `FORGE-PUB-0001` naming
the exact keys, rather than being rejected by the repository mid-upload.

`forge publish` stages the artifacts under `target/forge-publish/` in Maven
repository layout. Add `--upload` to send them to the repository named by
`[publish] repository` (or `[publish.snapshot] repository` for a `-SNAPSHOT`
version). For every artifact Forge also uploads the `.md5`, `.sha1`, `.sha256`
and `.sha512` checksums the repository validates, and re-uploading is
idempotent.

Credentials are read from `~/.forge/credentials.toml`, keyed by the same
repository name used in `[repositories]`, and are never taken from
`forge.toml`:

```toml
# ~/.forge/credentials.toml
[central]
username = "deployer"
password = "s3cr3t"
```

A rejected upload fails the command with `FORGE-PUB-0006` (URL and HTTP status
in the message); a malformed credentials file fails with `FORGE-PUB-0007`. GPG
signing lands in a later increment.

### Programmatic Configuration

You can also configure Forge programmatically using the ProjectConfig class:

```java
ProjectConfig config = new ProjectConfig();
config.setName("my-project");
config.setVersion("1.0.0");
config.setSourceDirectory("src/main/java");
config.setOutputDirectory("target/classes");

ProjectConfig.BuildSettings settings = new ProjectConfig.BuildSettings();
settings.setIncremental(true);
settings.setCacheEnabled(true);
config.setBuildSettings(settings);

ForgeEngine engine = new ForgeEngine(config);
ProjectAnalysis analysis = engine.build();
```

## Usage

Forge provides both command-line and programmatic interfaces for different use cases.

### Command-Line Usage

The CLI provides intuitive commands for common build operations:

```bash
# Display help information
forge help

# Build the project
forge build

# Compile and run the tests without packaging
forge test

# Clean all build artifacts
forge clean

# Discard the local build cache (content-addressed, always safe to drop)
forge cache clean

# Run the project's main class ([exec] main in forge.toml)
forge run

# Regenerate forge.lock from forge.toml (never done by a build)
forge lock refresh
forge update

# Show the resolved dependency tree (optionally filtered / depth-limited)
forge tree
forge tree --depth=2 slf4j

# Explain why a dependency is in the graph
forge why org.slf4j:slf4j-api

# Inspect resolution; --explain shows the full version derivation
forge resolve
forge resolve --explain

# List the available task types
forge tasks

# Diagnose environment and project problems (exit 2 when a check fails)
forge doctor
forge doctor --format=json

# Generate a CycloneDX SBOM of the resolved dependency graph
forge sbom
forge sbom --output=build/bom.json

# Stage release artifacts (POM, Gradle Module Metadata, jars, SBOM)
forge publish
forge publish --skip-build

# Stage and upload to the configured repository (credentials from ~/.forge)
forge publish --upload

# Multi-module: list modules in build order, or build one module and its deps
forge modules
forge build awesome.core
forge run awesome.app

# Build through the task graph (compile -> test -> package)
forge build graph

# Incremental build (only changed files)
forge build incremental

# Show version and configuration
forge info
```

### Programmatic Usage

For advanced integration, use the ForgeEngine API directly:

```java
// Create configuration
ProjectConfig config = new ProjectConfig();
config.setSourceDirectory("src/main/java");
config.setOutputDirectory("target/classes");

// Initialize engine
ForgeEngine engine = new ForgeEngine(config);

// Execute build
ProjectAnalysis analysis = engine.build();

// Access results
CompilationResult compilation = analysis.getCompilationResult();
int filesCompiled = compilation.getCompiledFiles().size();
```

### Incremental Builds

`forge build incremental` recompiles only what actually changed. Staleness is
decided by **content**, never by timestamps (AGENT.md section 3): each source
file's bytes are hashed, so a checkout, an rsync that preserves mtimes, or a
touch that only moves the clock never fool the cache. A failing compilation
fails the build and the process exit code, exactly like a full build.

```bash
forge build incremental   # recompile changed sources; exit != 0 on failure
```

```java
// Enable incremental compilation (default: true)
ProjectConfig config = new ProjectConfig();
ProjectConfig.BuildSettings settings = new ProjectConfig.BuildSettings();
settings.setIncremental(true);
config.setBuildSettings(settings);

// The engine recompiles only files whose content hash changed
ForgeEngine engine = new ForgeEngine(config);
engine.build();
```

### Multi-Module Projects

A multi-module project keeps each module's configuration in its own `forge.toml`. The root file declares only the shared settings and the module list:

```toml
# root forge.toml
[project]
name    = "awesome"
version = "1.0.0"
group   = "com.example"

[project.modules]
"awesome.core" = "${project.version}"
"awesome.app"  = "${project.version}"
```

Each module owns its own `forge.toml` and inherits version, group, repositories, toolchain and properties from the root. Modules depend on each other **by Maven coordinate, never by path**:

```toml
# awesome.app/forge.toml
[project]
name = "awesome.app"

[dependencies.compile]
"com.example:awesome.core" = "${project.version}"
```

Build order follows the inter-module dependency graph, and independent modules build in parallel:

```bash
# Show the module graph in build order
forge modules

# Build every module
forge build

# Partial build: only awesome.core and what it depends on
forge build awesome.core

# Run one module's [exec] main class
forge run awesome.app
```

Modules that depend on each other in a cycle are rejected before anything is built (`FORGE-MMD-0001`). A module with no `forge.toml` of its own fails with `FORGE-MMD-0002`. See `examples/multi-module` for a working project.

The project has a **single `forge.lock` at the root** covering the union of every module's dependencies (`forge lock refresh`), and `forge tree` / `forge why` / `forge resolve` read that same aggregate graph — so they answer for the whole project, not just the root. The lock is tied to a combined hash of the root and every module `forge.toml`: changing any of them without refreshing the lock fails the build with `FORGE-LCK-0003`.

### Variants

A **variant** is an explicitly selected overlay over the base configuration. There are no implicit profiles: a variant is applied only when you name it with `--variant=<name>` or the `FORGE_VARIANT` environment variable.

```toml
# forge.toml
[toolchain]
language = "11"

[variant.prod]
[variant.prod.toolchain]
language = "17"

[variant.prod.compile]
args = ["-Xlint:all", "-Werror"]
```

Application is deterministic: the selected `[variant.<name>]` table is merged over the base before parsing. Scalars and lists are **replaced**; tables are merged key by key, so a variant overrides only what it declares.

```bash
forge build --variant=prod
FORGE_VARIANT=prod forge test
forge lock refresh --variant=prod
```

In a multi-module project a variant is declared as `[variant.<name>]` in **any** `forge.toml` (root or module). Selection is global for the whole build, and module-level overrides win over inherited root values. A module that does not declare the selected variant still builds, with its base configuration, and Forge prints a notice listing it. If the variant is declared nowhere, the build fails with `FORGE-VRT-0001`.

Switching variants is not free by design: the variant name is folded into the lockfile `config_hash` and into the compile cache scope, so a build under one variant can never be served a lock or a cached artifact produced under another (`FORGE-LCK-0003`).

## Modules

Forge consists of several specialized modules, each responsible for a specific aspect of the build process.

### Core Module

The core module contains the essential build engine components:

- **ForgeEngine**: Orchestrates the entire build process, coordinating between different subsystems.
- **BuildAnalyzer**: Analyzes project structure, identifies source files, and calculates metrics.
- **Compiler**: Handles Java source compilation with support for incremental builds.
- **resolver/**: Maven Resolver (Aether) integration, conflict policy and lockfile generation.

### Cache Module

The cache module provides intelligent build caching:

- Reduces rebuild times by caching compilation results per source file.
- Invalidates entries by **content**, never by time (AGENT.md section 3): the
  key is a hash of the source bytes plus the dependency view, so a re-checkout
  or an `rsync -a` (same bytes, new mtime) still hits, while changed bytes miss.
- Distrusts a warm cache whose outputs are no longer on disk: if any expected
  class file is missing, the affected sources are recompiled instead of
  emitting an incomplete build.

### CLI Module

The command-line interface module provides:

- Intuitive command syntax for all build operations.
- Colored output for better readability.
- Help system with detailed command documentation.

### Model Module

The model module defines core data structures:

- **ProjectConfig**: Configuration settings for a project.
- **ProjectAnalysis**: Results of project analysis operations.
- **CompilationResult**: Outcome of compilation operations.
- **DependencyResolution**: Dependency resolution results.

## Building and Testing

Forge uses Maven for its own build process. The following commands are available:

### Build Commands

```bash
# Clean and compile
mvn clean compile

# Build the entire project
mvn clean install

# Skip tests during build
mvn clean install -DskipTests

# Package without running tests
mvn clean package -DskipTests
```

### Testing Commands

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ForgeEngineTest

# Run tests with verbose output
mvn test -X

# Generate test reports
mvn surefire-report:report
```

### Code Quality

```bash
# Verify the build (compile + tests)
mvn verify

# Generate the JaCoCo coverage report (bound to the verify phase)
mvn jacoco:report
```

## Contributing

Contributions are welcome and appreciated. To contribute to Forge:

1. Fork the repository on GitHub.
2. Create a new branch for your feature or bug fix.
3. Make your changes and ensure all tests pass.
4. Submit a pull request with a clear description of your changes.

Before submitting, please ensure:

- Your code follows the existing coding style.
- You have added appropriate test coverage.
- All existing tests continue to pass.
- Your commit messages are clear and descriptive.

## License

Forge is distributed under the MIT License. See the LICENSE file for more information.

---

For additional documentation, examples, and API references, please visit the [Forge GitHub repository](https://github.com/yasmramos/Forge).
