# AGENT.md — Forge Architecture Decision Record

This document records the architectural decisions for **Forge**, a next-generation JVM build system. It is the single source of truth for design intent. Contributors and AI agents working on this repository **must** read this file before making changes, and must not contradict these decisions without an explicit, documented revision here.

Each section states the decision and the reasoning behind it. Some decisions are held with conviction; others are documented bets, flagged as such.

---

## Conventions (non-negotiable)

- **Code, comments, Javadoc, and documentation are always written in English.**
- **Commit messages are in English and follow Conventional Commits**: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:` (with optional scope, e.g. `refactor(config): ...`).
- Work happens on the `develop` branch.
- Configuration format is **TOML** (`forge.toml`), parsed with **NightConfig**. JSON configuration is fully migrated away; do not reintroduce it.

---

## 1. Task Model

Designed around three concepts: **task**, **input**, **output**.

- **Task**: an atomic unit with a unique name, a type known by the core, declared inputs, and declared outputs. A task is *not* an arbitrary class invented by a plugin: it is an instance of a registered type. `compile`, `test`, `package`, `exec` are built-in types. Plugins may register new types, but the type exists before the instances.
- **Input**: anything that affects the result — files, property content, JDK version, declared environment variables, other tasks. Every input must be deterministically hashable. An input that cannot be hashed is a design error, not an exception.
- **Output**: what the task produces — files, directories, values. Outputs must be relocatable (no absolute paths) so remote caching works across machines.

**Graph**: directed and acyclic, explicit whenever possible. Each task declares what it depends on (by name or by type) and what it optionally depends on (soft dependencies to order without forcing execution). Cycle detection is mandatory before executing anything.

**Two-phase execution**:
1. *Configure time*: resolve the whole graph, validate inputs and outputs, compute hashes.
2. *Execution time*: only run tasks whose hash is not in cache.

If something will fail due to configuration, it must fail before touching anything — never mid-build.

**Parallelism**: over the graph. Tasks without dependency relationships run in parallel up to the thread limit. The scheduler is ours, not the OS's. Java virtual threads make this nearly free.

## 2. Dependency Resolution

- **Do not implement from scratch.** Use **Maven Resolver (Aether)** for Maven Central and Maven repositories. It is mature, it is the same engine as Maven, and it avoids years of compatibility bugs. Reinventing this is the kind of decision that kills projects.
- On top of Aether, build our own layer: `forge.toml` parsing, mapping to coordinates, version policy, and local/remote caching.

**Conflict policy**: neither "nearest wins" (Maven) nor "highest wins" (Gradle). Ours **fails by default** when there is a version conflict that cannot be resolved by exact alignment, with a clear message stating who brings what. Users can ease friction by declaring a forced version or using a BOM. Uncomfortable at first, but it is the only policy that yields predictable builds.

**BOMs**: first-class support. A BOM aligns versions without writing them all out. Without it, large projects are unfeasible.

**Dynamic versions** (`1.+`, ranges): allowed but discouraged by default. They are pinned in the lockfile. Without a lockfile, a range is a ticking time bomb.

**SNAPSHOTs**: allowed, but visibly flagged in output, with an explicit renewal policy (every build, hourly, or never). Maven renews them in mysterious ways by default; we will not.

**Downloads**: parallel with a cap, exponential-backoff retries, checksum validation always. If no checksum is available, warn. If a GPG signature exists and verification is enabled, verify it.

**Metadata**: support both Maven POM and **Gradle Module Metadata**. Gradle Module Metadata is required to consume modern Kotlin/JVM libraries that publish it. Ignoring it cuts our ecosystem in half.

## 3. Caching & Incrementality

This is Forge's differentiator. Done right from day one, because retrofitting caching is a thousand times more expensive than designing it well.

- **Local dependency cache**: `~/.forge/cache/deps/`. Deterministic structure keyed by coordinate hash. Never inside the project, so it is shared across projects of the same user. Immutable: once written, an entry never changes; if something changes, it becomes a new entry.
- **Local task cache**: `.forge/cache/` in the project, structured as `task-hash → outputs`. The hash is computed from: task name, task type, hash of each input, toolchain hash, Forge version hash, and the versions of every plugin that affects the task. Each component contributes to the key. If anything changes, the key changes, and the task re-runs.
- **Inputs**: by default, everything a task reads must be declared. Undeclared reads cannot be detected automatically, so the policy is: every built-in task type declares its inputs correctly, and plugins are required to do the same. A plugin that does not declare its inputs simply gets no caching benefit: it loses performance, but corrupts nothing.
- **Outputs**: each task declares its outputs. Outputs are stored compressed in cache, with relative paths. On restoration they are placed back in position. If an output exists on disk but is not in cache, it counts as a cache miss.
- **Remote cache**: simple HTTP, with our own well-documented protocol. Anyone can run a server on their infra, or use a managed service if one appears. Gradle Build Cache wire compatibility would be ideal but is not blocking; if Gradle's protocol is well specified, implement it as an optional plugin.
- **Invalidation**: never by time, always by content. A cached output is valid iff the hash of all its inputs is valid. No "newer than", no "older than". Content, not timestamps. This is what makes the cache work across git checkouts, across machines, and across CI.
- **Cross-project reuse**: task cache is per-project by default, with an opt-in "shared cache" per organization. In a large company this is worth a lot.

## 4. Plugin Model

Strong opinion: **plugins as jars on the core's classpath, with classloader isolation**. No WASM to start, no separate processes. Reason: the Java ecosystem exists and we want to exploit it. A Java plugin should be able to use any Java library without friction.

- **Isolation**: each plugin loads with its own classloader; one plugin's dependencies are not visible to another. This avoids the classpath-conflict hell Gradle suffers. A plugin declares its dependencies in its `forge-plugin.toml`, and Forge resolves them for it, isolated.
- **Extension API** — a plugin can:
  - Register new task types.
  - Register compilers for languages.
  - Register test runners.
  - Register packaging types.
  - Hook into existing tasks via declarative `before`/`after` hooks.
  - Add validations over the configuration file.
- **What a plugin cannot do**: arbitrarily modify existing core tasks, replace built-ins, or change another task's behavior without declaring it. Hooks are the only mechanism for extending behavior.
- **Versioning**: each plugin declares which core API version it was built against. The API is semver-versioned. If a plugin targets API 0.5 and the core is at 1.0, the core decides whether to accept it per compatibility policy. Incompatible changes bump the major version and break explicitly.
- **Registry**: not in 0.1. People install plugins by Maven coordinate. A dedicated registry can come later, but not before there are 10 plugins worth listing.
- **Discovery**: plugins are declared in `forge.toml` with a Maven coordinate. No autodiscovery magic. What is in the file gets loaded. What is not, does not.

## 5. Multi-module

A deliberate change from the current implementation:

- **Each module has its own `forge.toml`.** The root file only declares which directories are modules and the shared properties. Nothing else.
- Reason: as a project grows, one giant root file is a merge-conflict hell. Each team wants to touch its module without fighting the rest. It is the same problem monorepos have with Maven.
- **Inter-module dependencies by coordinate, not by path.** Each module publishes its artifact with a Maven coordinate, and other modules consume it exactly like any external dependency. This forces explicit dependencies and makes partial builds trivial.
- **Cycle detection between modules**: mandatory, before executing anything.
- **Partial builds**: `forge build awesome.core` compiles only that module and its dependencies. Essential in large monorepos.
- **Parallel execution**: modules without inter-dependencies compile in parallel. This is one of the biggest accelerators for large builds.
- **Configuration inheritance**: a module inherits shared things from the root (version, repositories, toolchain, properties) and may override anything. Clear rules for what is inheritable, root-only, or module-only. Nearly everything inheritable and overridable, except intrinsics like `[project] name`.

## 6. Publishing

Publishing to Maven Central is non-negotiable. It is the only registry that matters in the JVM ecosystem; if Forge cannot publish there, nobody will use it for serious libraries.

- **Flow**: `forge publish` packages, generates the POM, generates Gradle Module Metadata, signs with GPG, and uploads via the Maven Central protocol. Credentials live in a separate file, `~/.forge/credentials.toml`, which is never committed.
- **POM generation**: automatic, with all required fields. Users may override specific fields (license, developers, SCM). Generated POMs must be valid for Maven Central without manual edits.
- **Gradle Module Metadata**: generated automatically too, so Kotlin libraries published with Forge are consumable by Gradle without degradation.
- **Snapshots**: separate repository, clear retention policy. Snapshots are never published to Maven Central.
- **Versioning**: the version lives in `forge.toml`. Git tags are optional. We do not bind version to git tags because that is a decision each team must be able to make.

## 7. Lockfiles

Yes, by default. A `forge.lock` file, committed. It contains: all resolved dependencies (not just direct ones), exact versions, checksums, repository URLs, and the hash of the configuration that produced them.

- **When it updates**: only on explicit user commands (`forge update`, `forge lock refresh`). Never automatically during a build. A normal build uses the existing lockfile and fails if it does not match `forge.toml`. This forces dependency updates to be a conscious act.
- **Integrity verification**: checksums mandatory; GPG signatures optional. If a checksum mismatches, the build fails with a clear message.
- **Format**: TOML, consistent with the rest. Readable, diffable, with comments.

## 8. Profiles & Variants

A deliberate departure from Maven and Gradle: **no "profiles" as a first-class concept. Explicitly declared variants instead.**

- A project declares variants in `forge.toml` (e.g. `dev`, `prod`, `benchmark`), and each variant may override any part of the configuration. A variant is selected by CLI flag or environment variable. Never by implicit magic.
- Why not Maven-style profiles: they have automatic activation, complex inheritance, and are the #1 source of irreproducible builds. A variant must always be explicit.
- **Combination rules**: the selected variant is applied over the base configuration with clear per-section merge rules. Lists: replaced, unless explicitly declared otherwise. Predictability wins.

## 9. Own BOMs

A project may declare a `[bom]` section exporting a set of aligned versions. Any other project can consume that BOM as a dependency. Internally, when Forge resolves a BOM, it applies the versions to dependencies that do not pin their own.

Maven has had this for years and Gradle covers it with platforms. It is indispensable for large projects.

## 10. CLI

Main commands:

| Command | Purpose |
|---|---|
| `forge init` | Create a project from a template. |
| `forge build` | Compile, run tests, package. The default. |
| `forge test` | Tests only. |
| `forge run` | Execute. |
| `forge clean` | Clean. |
| `forge update` | Update the lockfile. |
| `forge publish` | Publish. |
| `forge why` | Explain why a dependency is in the graph. |
| `forge tree` | Dependency tree, with filters. |
| `forge doctor` | Diagnose environment problems. |
| `forge tasks` | List available tasks. |

- **Error handling**: every error must answer three questions: *what happened?*, *why?*, *how do I fix it?* If an error does not answer all three, it is badly written.
- **Progress**: by default, one compact progress line on a TTY. Without a TTY (CI), structured log lines. `--verbose` for detail, `--quiet` for errors only.
- **Stack traces**: hidden by default, shown only with `--debug`. A stack trace on a normal error is a sign of a badly designed message.

## 11. Logging

Structured logging by default, rendered human-readable on a TTY. Every event carries level, timestamp, associated task, and message. In CI, optional JSON output for parsing.

Logs go to **stderr**, not stdout. Stdout belongs to the program executed via `exec`, not to Forge's logs.

## 12. Network Error Handling

Dependency downloads retry with exponential backoff. After N failures, a clear error with URL, HTTP code, and repository. If one repository is down, Forge tries the next and reports it without failing the whole build if alternatives exist.

## 13. IDEs

Without IDE integration, Forge is not adopted. Period.

- **Phase 1**: generate project models IntelliJ and VS Code understand — `.idea/` for IntelliJ, `.classpath` for Eclipse, `.vscode/` settings for VS Code. This is "export the configuration to the IDE", not "the IDE understands Forge".
- **Phase 2**: a real IntelliJ plugin talking to Forge over our own protocol. Months of work.
- **Phase 3**: expose an API/LSP any IDE can consume. The right long-term answer.
- **Recommendation**: phase 1 from 0.5, phase 2 from 1.0.

## 14. Migration from Maven and Gradle

- `forge import` from a `pom.xml` or `build.gradle` generates a reasonable `forge.toml`. Not perfect, but reasonable: it covers 80% of cases and leaves `TODO` comments for the rest.
- **Incremental migration**: a Gradle project may have one sub-module using Forge and another using Gradle. No forced big-bang migration. Essential for enterprise adoption.

## 15. Documentation & Schema

- Publish a **JSON Schema / TOML Schema** for `forge.toml`. This gives IDE autocompletion (VS Code, IntelliJ TOML plugins) for free. Without it, people write config files blind.
- Error messages link to docs. Every error has a short URL explaining the problem.
- An online validator to share configs. Cheap to build, useful for debugging.

## 16. Daemon & Startup

**No mandatory daemon.** The CLI cold-starts every time. If startup is < 500ms, a daemon is unnecessary.

Two paths to get there:
- **GraalVM native-image** for the CLI. Eliminates JVM startup. This is what we would do from the start.
- If native-image causes problems, an **optional daemon** activated with `forge --daemon`, transparent to the user.

With native-image, Forge ships a single binary with instant startup and zero daemons — the biggest competitive UX advantage over Gradle and Maven.

The internal core can remain Java compiled to native. Plugins, however, need dynamic classloaders and stay pure JVM. Resolution: the CLI is native, and when plugins must be loaded, a helper JVM process is launched transparently. If there are no plugins, everything is native.

## 17. Reproducibility

Bit-for-bit reproducible builds by default: fixed timestamps in jars (based on `SOURCE_DATE_EPOCH`), deterministic classpath order, no variable metadata, no absolute paths in artifacts.

Harder than it looks, but regulated industries demand it and it is a real differentiator.

## 18. Security

- Mandatory checksums for all dependencies.
- Optional (recommended) GPG signature verification.
- **SBOMs** in CycloneDX format generated by default. A growing enterprise requirement.
- Vulnerability scanning integrated as an optional task `forge audit`, querying OSS Index or similar.

## 19. Observability

- **Local build scan**: `forge build --scan` generates an HTML timeline of tasks, bottlenecks, and cache hits/misses. No server required — just an HTML file you open in a browser. This is a Gradle Enterprise (paid) feature we offer for free. A selling point.
- Optional OpenTelemetry integration for those who want to ship metrics to their own system.

## 20. Toolchain Detection

Forge can download JDKs for you, as Gradle does with Foojay. The public Foojay API lists JDKs from multiple vendors. Using it is free and gives a much better experience: declare `language = "17"` and Forge handles the rest.

Downloaded JDKs are cached in `~/.forge/jdks/`.

## 21. Testing: Beyond Execution

- **Selective execution**: only tests affected by changes, based on the class dependency graph.
- **Flaky test retry**: optional, configurable.
- **Coverage**: integrated, backed by JaCoCo.
- **Reports**: HTML for humans, XML for CI (JUnit format).

## 22. Resources & Filtering

- Resource filtering with properties, like Maven — but **opt-in, not default**. Default-on filtering is a bug source (a binary file that happens to contain `${something}` gets corrupted).
- Resources are copied only if changed, by hash.
- `MANIFEST.MF` is generated automatically with the fields Maven/Gradle generate, plus whatever the user declares.

## 23. Annotation Processors

First-class support. Detected automatically from the compile classpath (a jar containing `META-INF/services/javax.annotation.processing.Processor`), or declared explicitly.

The processor classpath is **separate** from the compile classpath. Critical for Lombok, MapStruct, Dagger, etc.

Correct incremental processing is required. This is where IntelliJ and Maven fall down on large builds, and it is an opportunity to differentiate.

## 24. Environment Variables

The build may read env vars, but **only those explicitly declared**. No free access to `System.getenv()`. Every env var affecting the build must be declared as an input so the task hash includes it.

Config override by env vars: yes, with the `FORGE_` prefix. Useful in CI without touching files.

## 25. Telemetry

Opt-in, never opt-out. No telemetry by default. If someone enables it, it is an explicit command, and they can see exactly what is sent before turning it on.

---

## Order of Attack

1. Task model — without this there is nothing.
2. Dependency resolution with Aether — do not reinvent.
3. Lockfiles — reproducibility from day one.
4. Caching & incrementality — the differentiator.
5. CLI and error handling — first impression.
6. Plugin model — before anyone writes one.
7. Multi-module — before anyone tries it seriously.
8. Publishing — so others can consume you.
9. Migration from Maven — so people try you.
10. IDE integration phase 1 — so people stay.
11. GraalVM native-image — the UX advantage.
12. Build scan — the sales pitch.
13. Everything else.

## The Three Decisions That Define Forge

Where the project is truly won or lost:

1. **Native-image from the start.** If Forge starts in 200ms and Gradle in 3s, the argument is unbeatable. If we start in 2s, we are "just another Gradle".
2. **An impeccable task cache.** It separates a modern build system from an old one. If the cache fails or is fragile, we lose user trust and never get it back.
3. **Actionable error messages.** The #1 reason people hate Gradle is cryptic errors. If Forge has the best errors in the industry, people will forgive almost everything else.
