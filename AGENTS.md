# AGENTS.md — operational notes for AI agents working on Forge

`AGENT.md` (singular) is the architecture decision record and the source of
truth for design intent. This file is the *operational* companion: how to build,
test and extend the repository. Read both before changing anything.

## Build & test

```bash
mvn -q package -DskipTests        # produces target/forge-1.0.0-SNAPSHOT.jar
mvn test                          # full suite (419 tests as of section 21 selective tests)
./bin/forge build                 # run the CLI from the repo (needs the jar above)
```

The shaded jar is the only artifact the CLI needs; `bin/forge` finds it by
relative path, so rebuild after changing `src/main` before using it.

Fire-test the examples after touching compilation, packaging or resources:

```bash
cd examples/library && rm -rf target && ../../bin/forge build && ../../bin/forge run
```

`examples/multi-module` commits a `forge.lock`; the other examples do not
(their locks are gitignored and regenerated in CI).

## Conventions

- English everywhere: code, comments, Javadoc, docs, commit messages.
- Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`,
  `chore:`), optional scope, e.g. `feat(compile): ...`.
- Work on `develop`; PRs go `develop -> main`.
- Tests use real code paths. Do not introduce mocks.
- Configuration is TOML only, parsed with NightConfig.

## Where things live

| Area | Package / file |
|---|---|
| Build orchestration | `core/ForgeEngine.java` |
| javac invocation (in-process + external fallback) | `core/Compiler.java` |
| Task graph, hashing, cache keys | `core/task/` |
| Dependency resolution (Aether) | `core/resolver/`, `core/DependencyScopes.java` |
| Lockfile | `core/lockfile/Lockfile.java`, `core/resolver/LockfileGenerator.java` |
| Plugins (`forge-plugin.toml`) | `core/plugin/` |
| Resources + filtering | `core/resource/ResourceProcessor.java` |
| Annotation processors | `core/processor/AnnotationProcessors.java` |
| CLI dispatch | `cli/ForgeCLI.java`, `cli/command/` |
| Error catalog (stable codes) | `cli/error/ErrorCode.java` |
| Config variants (8/8bis) | `utils/ConfigReader.java` (`parseConfig(root, props, variant)`) |
| Resolved-config inspection | `cli/command/ConfigCommand.java` (`forge config`) |
| Toolchain language + `[compile].args` | `core/Compiler.java` (`setToolchain`, `setCompilerArgs`) |
| Import from Maven/Gradle (`forge import`) | `cli/command/ImportCommand.java`, `core/importer/` |
| Build scan (`forge build --scan`) | `cli/command/BuildScanCommand.java`, `core/scan/BuildScanGenerator.java` |
| Vulnerability audit (`forge audit`) | `cli/command/AuditCommand.java`, `core/audit/OssIndexClient.java` |
| Minimal JSON reader (audit) | `core/audit/Json.java` |
| IDE export (`forge ide`, phase 1) | `cli/command/IdeCommand.java`, `core/ide/` |
| Selective tests + flaky retry (21) | `core/test/SelectiveTests.java`, `core/test/TestExecutor.java`, `core/test/JUnitRunner.java` |

## Non-obvious invariants

- **Compilation uses `-proc:none` only when nothing declares a processor.**
  Discovery scans *resolved dependency artifacts*, never the whole classpath
  string: the string also carries Forge's own runtime libraries, and a
  processor hiding there must not run against user code.
- **A single root `forge.lock` covers the whole graph** (sections 7bis/8bis).
  `Lockfile.hashOf(namedConfigs(...))` hashes root + every module `forge.toml`.
  A mismatch is `FORGE-LCK-0003` at build time; locks are never auto-repaired.
- **Cache validity is content-based, never time-based** (section 3). Never add a
  timestamp comparison to invalidation logic.
- **The selected variant must invalidate lock and cache** (sections 8bis/3).
  The variant name is folded into `Lockfile.hashOf(..., variant)` (→ `config_hash`)
  and into `ProjectConfig.getCacheScopeId()` (→ compiler `projectId`). A cached
  artifact or a lock produced under one variant must never be reused under
  another. Both use logical names only, never paths, so hashes stay portable.
- **Variant merge order is fixed** (section 8bis): inherit(root resolved-with-
  variant) → module base overrides → module `[variant.<name>]`. `[variant.*]` is
  selection metadata and is stripped before parsing the effective config.
- **Hashes must be portable** (section 17): relative paths only, no absolute
  paths or variable metadata in cache keys or artifacts.
- **`[toolchain].language` drives `-source`/`-target`** (section 20), and
  `[compile].args` are extra javac flags appended *after* Forge's own options so
  an explicit user argument wins. Both are emitted only when declared; an empty
  toolchain leaves javac's default (the running JDK) untouched. `[compile].args`
  is a declared task input, so changing it re-runs the compile instead of
  restoring a stale cached output (section 3).
- **`forge import` never overwrites and never invents data** (section 14).
  It refuses to touch an existing `forge.toml` (`FORGE-IMP-0003`), parses XML
  without fetching external DTDs, and turns anything it cannot translate into a
  visible `TODO` instead of a guess. A dependency without a declared version is
  emitted commented out, never given a made-up version, so the generated file is
  always parseable TOML. Repository keys are quoted: an unquoted dotted key
  (`jitpack.io`) parses as a nested table in TOML and would silently drop the
  repository.
- **The build scan is self-contained and reproducible** (section 19). It writes
  a single HTML file with inline CSS and no external assets, so it opens offline
  and can be archived. Its timestamp comes from the same `SOURCE_DATE_EPOCH`
  chain as the rest of the build, so two identical builds produce byte-identical
  scans. Task names are HTML-escaped before rendering.
- **`forge audit` is optional, off the build path, and never lies** (section 18).
  It scans the *resolved* graph (same graph as `forge tree`/`why`), so its
  answers cannot disagree with resolution. An unreachable service is a
  structured `FORGE-NET-0001` (exit 2, environment), never a silently clean
  report; `--offline` is the explicit opt-out. Its exit code gates CI: non-zero
  when a finding is at or above `[build] audit_fail_on` (default `high`, `none`
  never fails). The OSS Index client batches at the API limit (128), retries
  with backoff, and reads responses with the hand-written `core/audit/Json`
  reader because Forge ships no JSON library.
- **`forge ide` is a one-way export, derived from the build model** (section 13,
  phase 1). It writes the IntelliJ/Eclipse/VS Code files from the *same*
  configuration and Aether-resolved classpath the compiler uses, so the IDE
  classpath cannot drift from the build classpath. Forge never reads these files
  back: the model is refreshed by re-running the command, never by editing the
  generated files. Source/resource roots are project-relative (portable, section
  17); library paths are absolute (IDEs reference jars by location). The test
  scope lists only libraries *not already* on the compile scope, so no jar is
  emitted twice. Generators are pure (`core/ide/IdeGenerators`) and the command
  is a thin writer, so the models are unit-tested without a filesystem.
- **Selective tests are opt-in and never under-run silently** (section 21).
  `[test] selective = true` runs only the tests a change can affect, derived
  from the same `SourceDependencies` Merkle graph the compiler uses for cache
  invalidation, so the selection and the cache agree by construction. It is
  conservative by design: no baseline, a corrupt state file, or any resource
  change all fall back to running every test, and state is written only after a
  fully green run so a failure is never remembered as a pass. State lives in
  `.forge/test-state.toml` with project-relative POSIX paths and content hashes
  (portable, section 17; content-based, never time-based, section 3). Flaky
  retry (`[test] retry_failed`) re-runs a failing class and *reports* the class
  as flaky, never silently as a pass.
- **Error codes are public API.** Add new ones to `cli/error/ErrorCode.java`
  with what/why/how/docs; never reuse or renumber a shipped code.

## Current state

Sections 22 (resources), 23 (annotation processors), 8/8bis (variants), the
`forge config` command, the `[toolchain].language` → `-source`/`-target`
wiring, `forge import` (section 14), the local build scan `forge build --scan`
(section 19, single-project builds), the vulnerability audit `forge audit`
(section 18), IDE integration phase 1 `forge ide` (section 13: IntelliJ,
Eclipse and VS Code models) and selective test execution + flaky retry
(section 21, opt-in via `[test]`) are implemented and committed. Section 10's
command table is complete.
