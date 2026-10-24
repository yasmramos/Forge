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

## 0. Non-goals (what Forge is *not*)

Scope must be bounded explicitly, or contributors and users will fill the gaps with wrong expectations.

- **No Android support.** Android builds are a platform, not a build system: AAPT2, DEX, Gradle plugin APIs, Play publishing. Supporting it is a multi-year project on its own. Forge targets plain JVM artifacts. If Android ever happens, it happens as an external plugin ecosystem, never in core.
- **No native toolchains (C/C++/Rust) in core.** JNI projects are supported only insofar as they are ordinary JVM projects with extra resources and a packaged `.so`/`.dll`. Forge does not compile native code.
- **WASM is out of scope for both plugins and build outputs** (at least through 1.x). We rejected WASM plugins for ecosystem reasons (§4); we do not target WASM as a compilation output either. If someone wants it, it is a plugin candidate later, not a core feature.
- **Language support tiers**: Java is built-in from day one. Kotlin and Scala are the first plugin-provided languages (§4 compiler registration exists precisely for them). Mixed Java+Kotlin in one module is supported via joint-compilation ordering rules owned by the Kotlin plugin, not by core. Groovy and other JVM languages: possible via plugins, no core promises.
- **Not a CI system, not an artifact repository, not a dependency vulnerability database.** Forge integrates with those (build scans, remote cache servers, `forge audit` querying OSS Index), but does not become them.

These boundaries exist so that "Forge is fast and predictable for JVM projects" stays true. Every future feature proposal must survive this list before reaching the roadmap.

---

## 1. Task Model

Designed around three concepts: **task**, **input**, **output**.

- **Task**: an atomic unit with a unique name, a type known by the core, declared inputs, and declared outputs. A task is *not* an arbitrary class invented by a plugin: it is an instance of a registered type. `compile`, `test`, `package`, `javaExec` are built-in types (note: `javaExec`, not `exec` — see §10's naming reconciliation). Plugins may register new types, but the type exists before the instances.
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

### 2bis. Conflict Resolution Mechanics

"Fail by default" (§2) is the policy; this section defines the mechanics, because without precise definitions we would fail on cases users consider benign.

- **What a conflict is (precise definition)**: two *different exact versions* selected for the same `groupId:artifactId` after applying, in order: (1) direct constraints from `forge.toml`, (2) BOM/platform-imported constraints, (3) transitively requested versions. A conflict exists iff more than one distinct version survives that ordering with no single winner. Two paths requesting the *same* version is not a conflict. A range (`[1.2,2.0)`) is not itself a conflict: ranges are resolved against candidates at lock time and pinned in `forge.lock`; if no candidate satisfies all intersected ranges, that is reported as an unsatisfiable-range error, distinct from a version conflict.
- **Alleviation mechanisms (three, not two)**:
  1. **Forced version / constraint**: declare an exact version in `[dependencies]` or a `[constraints]` section; wins over everything transitive.
  2. **BOM import**: aligns whole version sets (§9).
  3. **`resolutions` override**: a dedicated, explicit section — `[resolutions]` — where the user pins a coordinate "no matter what": `"com.foo:bar" = "2.3.1"`. This is Forge's answer to Gradle's resolution strategy, but deliberately narrow: per-coordinate pins only, no arbitrary rules-language. Every applied resolution must be visible in `forge why` output (see below), so overrides can never hide silently.
- **`forge resolve --explain`**: before failing, and on demand, Forge prints the full derivation: which graph paths request which version of each conflicted coordinate, which constraint won and why (rule number above), and which lever (forced version, BOM, or resolution) would fix it. The conflict error message itself embeds a condensed version of this explanation plus the docs link (§10bis). Without this command, "fail by default" is just friction; with it, it is guidance.
- **Interaction with the lockfile**: conflicts are detected at *lock* time (`forge update`/`forge lock refresh`), not at build time. A build against a valid `forge.lock` never discovers new conflicts — reproducibility first.

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

### 4bis. The `forge-plugin.toml` Contract

If plugins are jars carrying their own TOML, that TOML *is* part of Forge's public API and must be specified, versioned, and validated like any other public surface. This is the contract:

```toml
# forge-plugin.toml — located at the root of the plugin jar.
schema = "0.1"                       # plugin-descriptor schema version, independent of core

[plugin]
id       = "io.example.forge.kotlin" # globally unique, reverse-DNS, stable forever
name     = "Kotlin support"          # human-readable display name
version  = "1.2.0"                   # plugin's own semver
api      = ">=0.3 <1.0"              # core API version range this plugin was built against
main     = "io.example.forge.kotlin.KotlinPlugin"  # entry point implementing io.github.yasmramos.forge.plugin.ForgePlugin

[dependencies]                       # resolved by Forge, loaded into THIS plugin's isolated classloader
"org.jetbrains.kotlin:kotlin-compiler-embeddable" = "1.9.22"

[[provides]]                         # optional, declarative summary for diagnostics and `forge tasks`
type = "task-type"
name = "kotlinCompile"

[compatibility]
reproducible = true                  # see §17: false triggers loud warnings on reproducible builds
```

Rules:

- **`id` + `version`** form the plugin's identity. The id never changes; renaming means a new id plus a deprecation notice.
- **`api` range** is checked before loading. A plugin whose range does not include the running core API version fails to load with error `FORGE-PLG-0001` explaining both versions and pointing to upgrade notes. We never half-load an incompatible plugin.
- **`[dependencies]`** use the same coordinate syntax as `forge.toml`, are resolved through the same engine (§2), and land exclusively in the plugin's isolated classloader (§4). Core classes visible to plugins come only from the explicit `forge-plugin-api` artifact; anything else is an implementation detail plugins must not touch (enforced by review + an exported-packages list in the API jar manifest).
- **`[[provides]]`** is informational (for `forge tasks`, build scans, and conflict reporting between plugins registering the same extension point). Actual registration happens in code via the entry point; if `provides` and runtime registrations disagree, the build fails — drift between declaration and behavior is exactly the kind of surprise Forge exists to eliminate.
- **Validation**: descriptor parsing errors fail fast at configure time with §10bis-style messages (code, cause, fix, docs link). The descriptor schema is published alongside the `forge.toml` schema (§15) so IDEs can autocomplete it too.

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

### 7bis. The `forge.lock` Format

A lockfile is not a configuration file — it is a contract between Forge and the repository. "Readable TOML" is not enough; it needs its own versioned, stable schema:

```toml
# forge.lock — generated by Forge. Do not edit manually.
lockfile_version = "0.1"             # schema of THIS file, independent of forge.toml's `forge` key
config_hash      = "sha256:9c1e..."  # hash of the resolved config (incl. selected variant) that produced this lock

[generated]
forge = "0.3.1"                      # informational only — never part of validity checking
timestamp = "2026-10-25T14:00:00Z"   # informational only — never part of validity checking

[[artifact]]                         # one entry per RESOLVED dependency (transitive closure included)
coordinate = "org.slf4j:slf4j-api"
version    = "2.0.7"
repository = "central"               # key into [repositories], so mirrors can be remapped at build time
sha256     = "sha256:ab12..."        # mandatory integrity anchor (§2, §18)
requested  = ["awesome-core->1.0.0"] # provenance paths, rendered by `forge why` / `forge resolve --explain`

[[artifact]]
coordinate = "ch.qos.logback:logback-classic"
...
```

Rules:

- **Own schema version** (`lockfile_version`), decoupled from `forge.toml`'s `forge` key. A Forge release can read older lock schemas via explicit migration; unknown newer versions fail with `FORGE-LCK-0002` telling the user to upgrade Forge.
- **Stable serialization**: entries sorted by coordinate then version; keys in fixed order; canonical formatting enforced by the writer. Diffs must show *only* real changes — if reordering produces noise, the serializer has a bug. This is a tested invariant, not a style preference.
- **Merge policy**: lockfiles are **never hand-merged**. On a git conflict, resolve by taking either side (or deleting the file) and running `forge lock refresh`, which regenerates deterministically from `forge.toml`. We deliberately do not attempt automatic semantic merging: two branches adding dependencies should produce a regenerated lock containing both, and regeneration is cheap and always correct.
- **Self-integrity**: the final line is `content_hash = "sha256:..."` over everything above it. A build verifies it before trusting any entry; tampering or partial edits fail immediately with `FORGE-LCK-0001`. This catches accidental corruption and manual edits without pretending to provide cryptographic guarantees about authorship (that would need signing infrastructure; GPG-signing locks is optional, §7).
- **Validity check at build time**: the build recomputes `config_hash` from the current resolved configuration (variant applied, §8/§8bis). Mismatch → `FORGE-LCK-0003` ("configuration changed since last lock; run `forge lock refresh`"). Never auto-repair during a build (§7).

## 8. Profiles & Variants

A deliberate departure from Maven and Gradle: **no "profiles" as a first-class concept. Explicitly declared variants instead.**

- A project declares variants in `forge.toml` (e.g. `dev`, `prod`, `benchmark`), and each variant may override any part of the configuration. A variant is selected by CLI flag or environment variable. Never by implicit magic.
- Why not Maven-style profiles: they have automatic activation, complex inheritance, and are the #1 source of irreproducible builds. A variant must always be explicit.
- **Combination rules**: the selected variant is applied over the base configuration with clear per-section merge rules. Lists: replaced, unless explicitly declared otherwise. Predictability wins.

### 8bis. Variants in Multi-module Projects

§5 gives each module its own `forge.toml`; §8 declares variants there. Left implicit, this collides on the first real multi-module project. The rules:

- **Declaration**: a variant is declared as `[variant.<name>]` in *any* `forge.toml` — root or module. A module may declare variants the root does not know about; that is legitimate (module-specific concerns like `benchmark` on one hot module).
- **Selection is global, resolution is per-module**: one variant name is selected per invocation (`--variant=prod` or `FORGE_VARIANT=prod`) and applies to the whole build — never per-module selection within one invocation. Mixed variants inside a single build would poison inter-module reproducibility (§17) and dependency alignment (§2), so we refuse it outright.
- **Unknown variant → warning, not error, not silence**: if the selected variant exists in some modules but not others, the modules lacking it build with their base configuration and Forge prints an explicit notice listing which modules ignored the variant. Silently ignoring it is exactly the Maven-profile disease (§8). If the selected variant exists *nowhere*, the build fails with `FORGE-VRT-0001`.
- **Application order** (deterministic, documented): module config = inherit(root resolved-with-variant) → apply module base overrides → apply module's own `[variant.<selected>]` overrides. Module variant overrides always win over inherited root variant values. Every layer is visible via `forge config --show-resolved <module>`.
- **Lockfiles**: each module's lock entry set contributes to the root lock (one `forge.lock` at the root covering the full graph, §7 extended); `config_hash` includes the selected variant name and the merged variant layers, so switching variants invalidates the lock check loudly instead of silently resolving differently.

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
| `forge resolve` | Resolve/inspect the dependency graph; `--explain` shows version-derivation (§2bis). |
| `forge doctor` | Diagnose environment problems. |
| `forge tasks` | List available tasks. |

- **Error handling**: every error must answer three questions: *what happened?*, *why?*, *how do I fix it?* If an error does not answer all three, it is badly written. See §10bis for the underlying error model.
- **Progress**: by default, one compact progress line on a TTY. Without a TTY (CI), structured log lines. `--verbose` for detail, `--quiet` for errors only.
- **Stack traces**: hidden by default, shown only with `--debug`. A stack trace on a normal error is a sign of a badly designed message.
- **`exec` vs `run` — naming reconciliation**: `forge run` is a **command**, not a graph task: it launches the application and owns stdout/stderr passthrough (§11). The built-in task type formerly sketched as `exec` is renamed **`javaExec`** (and plugin process-tasks follow the same convention): it is part of task chains (e.g. generate-code → compile), its stdout is captured by Forge (streamed to stderr in `--verbose`, included in failure reports), and it never writes to the build's stdout. One word, one meaning: *run* = user-facing execution command; *javaExec* = graph task that invokes a JVM process. This removes the ambiguity where "exec" meant both.

### 10bis. Error Model

"Answer three questions" (§10) is a style rule; this is the machinery that makes it enforceable across a growing codebase.

- **Every error is a structured value, never a bare string.** Central type (implemented today in `cli/error/ForgeException`, to be hardened here):

  ```java
  ForgeError {
      String  code;          // stable identifier, e.g. "FORGE-LCK-0003"
      String  what;          // one sentence: what happened
      String  why;           // cause chain, concrete values included
      String  how;           // actionable fix, exact command when possible
      String  docsUrl;       // short link, e.g. https://forge.dev/e/LCK-0003
      List<TaskFrame> trace; // accumulated task context, see below
      ForgeError cause;      // optional wrapped upstream error (never a raw Throwable at API boundaries)
  }
  ```

- **Error code catalog**: codes are `<PREFIX>-<NNNN>` with domain prefixes (`CFG` config, `DEP` resolution, `LCK` lockfile, `TSK` graph/tasks, `PLG` plugins, `NET` network, `CCH` cache, `VRT` variants, `RUN` execution…). Codes are **stable public API**: once shipped, a code is never reused or renumbered; deprecating an error means retiring its code. The catalog lives in a single machine-readable file (`docs/errors.toml`) validated by tests: no error may be thrown with an unregistered code, and every registered code must have what/why/how/docs fields. CI enforces this, so "good errors" stops depending on reviewer vigilance.
- **Context accumulation**: when an error surfaces inside task X which was required by Y which was required by Z, the CLI renders the chain as frames — `at package ← compile ← awesome-core:compile` — plus inputs snapshot relevant to the failing task. Errors carry their own context because by the time they reach the CLI, the call stack no longer knows which task was running.
- **Rendering policy**: human format on TTY (code in bold, what/why/how blocks, docs link last); JSON-per-line in CI (`--format=json`) so tooling can key on `code`; full Java stack traces only under `--debug`, appended after the structured block. Exit codes map by class: 1 = user/config error, 2 = environment error, 3 = internal bug (which we treat as a bug report worth filing).

## 11. Logging

Structured logging by default, rendered human-readable on a TTY. Every event carries level, timestamp, associated task, and message. In CI, optional JSON output for parsing.

Logs go to **stderr**, not stdout. Stdout belongs to the program executed via `forge run` (§10: the command, not a graph task), not to Forge's logs. Graph tasks that spawn processes (`javaExec` and plugin process-tasks) have their output captured by Forge, never written to the build's stdout.

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

### 16bis. Native Core + JVM Plugin Execution Model (documented bet)

The paragraph above is the *direction*, not a finished design. This section records the open questions honestly and the decision we are betting on, because a hybrid split done wrong is fragile in exactly the way §1 warns about.

**The problem**: a native image cannot execute plugin bytecode (no dynamic classloading), yet a plugin-registered task type may be needed by the very graph the native core schedules. Splitting one build across two processes invites duplicated hashing logic, split cache ownership, and inconsistent output directories.

**Decision (revised)**: **the split is per-build, not per-task.** There is no "some tasks run native, some run in the helper." Exactly two execution modes exist:

1. **Pure-native mode** — the project declares no plugins, or only plugins marked `core-hostable = true` (a tiny subset that registers nothing requiring bytecode execution: config validators, repository/auth providers). The native binary resolves, hashes, schedules, runs built-in actions, writes caches, and renders the build scan. No JVM process exists.
2. **Full-JVM mode** — the moment any real plugin is present, the native binary becomes a **launcher**: it locates/verifies the bundled JVM runtime (or an installed one ≥ the required version), spawns the *same codebase* running on the JVM, forwards the command line, and pipes structured events back for rendering. The JVM process executes the *entire* build — configure, hash, schedule, all tasks including built-ins — using the identical core classes as native mode. One build, one brain.

Consequences of choosing per-build granularity:

- **No duplicated logic.** Hashing, cache read/write, lockfile validation, and build-scan generation live in core classes that run either on native or on JVM — never reimplemented on the "other side." The launcher does not compute anything; it only starts, streams, and exits-with-code.
- **Cache ownership is unambiguous.** Whoever executes the build owns the cache writes. Remote cache upload happens at end-of-build in the executing process, so a mid-build crash simply means nothing was uploaded (safe: entries are immutable, §3).
- **Build scans are generated by the executor**, from its own event stream; the launcher just prints the path.
- **Communication protocol**: newline-delimited JSON-RPC-lite over the child's stdout/stderr is *not* used for logs — instead, a dedicated pipe pair (fd 3/4) carries framed events (`task-started`, `task-finished`, `cache-hit`, `error`) while stdout/stderr belong to the child normally. The native launcher consumes events to render the progress line (§10). Keeping the event channel off stdout avoids corrupting `forge run` passthrough semantics (§11).
- **Startup cost honesty**: full-JVM mode pays JVM startup (~50–150 ms with CDS/AppCDS on a small heap — acceptable) but keeps native-mode's <500 ms promise for plugin-free projects, which we expect to be the majority of 0.x usage.

**What remains genuinely open (flagged bets)**: (a) bundling a JRE per platform vs requiring an installed JDK — leaning toward jlink'd minimal runtime bundled in releases; (b) whether `core-hostable` plugins can eventually include task types via AOT-generated adapters — parked until GraalVM reachability data tells us it is worth it; (c) Windows launcher process-management details. None of these change the per-build rule, which is the load-bearing decision.

## 17. Reproducibility

Bit-for-bit reproducible builds by default **for core tasks**: fixed timestamps in jars, deterministic classpath order, no variable metadata, no absolute paths in artifacts.

Precision matters here, because "by default" without scope promises more than we can deliver:

- **Scope of the guarantee**: reproducibility applies to *artifacts* (jars, archives, generated POM/module metadata), not to the *process*. Two builds producing byte-identical outputs may still differ internally (JIT, thread interleaving, log ordering) — that is fine and expected. Tests' timing variance is explicitly out of scope of the guarantee.
- **Plugins are best-effort**: a plugin writing a timestamp or absolute path into an output breaks reproducibility, and core cannot fully prevent it. Policy: the plugin descriptor declares `[compatibility] reproducible = true|false` (§4bis); Forge trusts `false` silently, and for `true` plugins it **verifies by double-run sampling** (cheap: build twice into scratch, compare hashes) in CI-facing commands. When a non-reproducible output is detected from a plugin claiming `reproducible = true`, Forge **warns loudly** with the offending artifact and plugin id (`FORGE-RPR-0001`) — never fails the build, never stays quiet. Projects that need a hard guarantee set `build.reproducible = "strict"` and get failure instead of warning.
- **`SOURCE_DATE_EPOCH` resolution order** (must be deterministic): (1) explicit `SOURCE_DATE_EPOCH` env var if set; (2) `[build] reproducible_timestamp` in `forge.toml` if declared; (3) commit timestamp of HEAD if the project is a git checkout; (4) fallback `1980-02-01T00:00:00Z` (ZIP's epoch minimum), logged once per build so the choice is visible. Git-tag-based dates are deliberately excluded — versioning is not bound to tags (§6).
- **JVM nondeterminism hygiene**: core serializes every map/collection entering an artifact in sorted order, pins jar entry order to lexicographic, and normalizes manifest attribute order. These are tested invariants, not aspirations.

Harder than it looks, but regulated industries demand it and it is a real differentiator — *within the scoped guarantee above*.

## 18. Security

Security is not a separate phase — its pieces belong to the subsystems they protect, and are cross-referenced where they live:

- **Mandatory checksums for all dependencies** — this is part of dependency resolution (§2/§2bis) and lockfile integrity (§7bis), not an add-on: no artifact enters the graph without a verified hash, enforced at lock time and re-verified at download time.
- Optional (recommended) GPG signature verification (§2).
- **SBOMs in CycloneDX format generated by default during `forge publish`** (§6) — the moment artifacts leave the machine is the moment provenance matters most; generation also available on demand via `forge sbom`. A growing enterprise requirement.
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

**Reconciling with §4's "no autodiscovery"**: these are different layers, and the distinction is deliberate. Plugins affect *build configuration* — they change what Forge itself does — so they must be explicit in `forge.toml`. Annotation processors are part of the *content of dependencies*: declaring a dependency on Lombok already declares the intent to run its processor; discovering it inside the jar is honoring that declaration, not magic. Rule of thumb: nothing that changes build behavior enters the system without appearing in a config file; things that only affect compilation semantics may follow standard Java service discovery.

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
2. Dependency resolution with Aether — do not reinvent; **includes mandatory checksums (§2/§18) and conflict mechanics (§2bis)**.
3. Lockfiles — reproducibility from day one; format per §7bis (schema version, self-integrity, merge policy).
4. Caching & incrementality — the differentiator.
5. CLI and error handling — first impression; **error model (§10bis) lands with this step**, because every prior step already emits errors that must follow it.
6. Plugin model — before anyone writes one; **`forge-plugin.toml` contract (§4bis) freezes here**.
7. Multi-module — before anyone tries it seriously; variant propagation per §8bis.
8. Publishing — so others can consume you; **SBOM generation rides along (§18)**.
9. Migration from Maven — so people try you.
10. IDE integration phase 1 — so people stay.
11. GraalVM native-image — the UX advantage; execution model per §16bis.
12. Build scan — the sales pitch.
13. Everything else (audit task, selective tests, OpenTelemetry, daemon fallback).

## The Three Decisions That Define Forge

Where the project is truly won or lost:

1. **Native-image from the start.** If Forge starts in 200ms and Gradle in 3s, the argument is unbeatable. If we start in 2s, we are "just another Gradle".
2. **An impeccable task cache.** It separates a modern build system from an old one. If the cache fails or is fragile, we lose user trust and never get it back.
3. **Actionable error messages.** The #1 reason people hate Gradle is cryptic errors. If Forge has the best errors in the industry, people will forgive almost everything else.
