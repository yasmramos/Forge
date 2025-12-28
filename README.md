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
│   │   └── DependencyResolver.java   # Dependency management
│   ├── cli/                          # Command-line interface
│   │   └── ForgeCLI.java
│   ├── model/                        # Data models and configurations
│   ├── utils/                        # Utility functions
│   └── config/                       # Configuration management
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

# Analyze project structure
java -jar target/forge-1.0.0-SNAPSHOT.jar analyze

# Clean build artifacts
java -jar target/forge-1.0.0-SNAPSHOT.jar clean
```

For a new project, create a `forge.config` file in your project root:

```properties
# Project Configuration
project.name=my-project
project.version=1.0.0
source.directory=src/main/java
test.directory=src/test/java
output.directory=target/classes
```

## Configuration

Forge supports configuration through properties files and programmatic APIs. The configuration system is flexible and allows customization of all aspects of the build process.

### Configuration File Format

Configuration files use standard Java properties format:

```properties
# Core Settings
project.name=my-project
project.version=1.0.0
source.directory=src/main/java
test.directory=src/test/java
output.directory=target/classes
build.directory=target

# Compilation Settings
compiler.source=11
compiler.target=11
compiler.debug=true
incremental.compilation=true

# Dependency Settings
dependencies.repository=https://repo.maven.apache.org/maven2/
offline.mode=false

# Cache Settings
cache.enabled=true
cache.directory=.forge-cache
cache.expiration.hours=24
```

### Programmatic Configuration

You can also configure Forge programmatically using the ProjectConfig class:

```java
ProjectConfig config = new ProjectConfig();
config.setProjectName("my-project");
config.setSourceDirectory("src/main/java");
config.setOutputDirectory("target/classes");
config.setIncrementalCompilation(true);

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

# Clean all build artifacts
forge clean

# Analyze project structure
forge analyze

# Run tests
forge test

# Package artifacts
forge package

# Incremental build (only changed files)
forge build --incremental
```

### Programmatic Usage

For advanced integration, use the ForgeEngine API directly:

```java
// Create configuration
ProjectConfig config = new ProjectConfig();
config.setSourceDirectory("src/main/java");
config.setTestDirectory("src/test/java");

// Initialize engine
ForgeEngine engine = new ForgeEngine(config);

// Execute build
ProjectAnalysis analysis = engine.build();

// Access results
CompilationResult compilation = analysis.getCompilationResult();
int filesCompiled = compilation.getCompiledFiles().size();
```

### Incremental Builds

Forge's incremental build feature significantly speeds up rebuilds by only processing changed files:

```java
// Enable incremental compilation
ProjectConfig config = new ProjectConfig();
config.setIncrementalCompilation(true);

// The engine will track file changes and only recompile what's necessary
ForgeEngine engine = new ForgeEngine(config);
engine.build();
```

## Modules

Forge consists of several specialized modules, each responsible for a specific aspect of the build process.

### Core Module

The core module contains the essential build engine components:

- **ForgeEngine**: Orchestrates the entire build process, coordinating between different subsystems.
- **BuildAnalyzer**: Analyzes project structure, identifies source files, and calculates metrics.
- **Compiler**: Handles Java source compilation with support for incremental builds.
- **DependencyResolver**: Manages project dependencies and their resolution.

### Cache Module

The cache module provides intelligent build caching:

- Reduces rebuild times by caching compilation results.
- Automatically invalidates cache entries when dependencies change.
- Supports configurable cache expiration policies.

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
# Run code analysis
mvn analyze

# Check for code style violations
mvn checkstyle:check

# Generate code coverage report
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
