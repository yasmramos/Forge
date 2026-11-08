# AGENTS.md — operational notes for AI agents working on Forge

`AGENT.md` (singular) is the architecture decision record and the source of
truth for design intent. This file is the *operational* companion: how to build,
test and extend the repository. Read both before changing anything.

## Build & test

```bash
mvn -q package -DskipTests        # produces target/forge-1.0.0-SNAPSHOT.jar
mvn test                          # full suite (323 tests as of section 23)
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
- **Error codes are public API.** Add new ones to `cli/error/ErrorCode.java`
  with what/why/how/docs; never reuse or renumber a shipped code.

## Current state

Sections 22 (resources), 23 (annotation processors), 8/8bis (variants) and the
`forge config` command are implemented and committed. Section 10's command table
is still missing `forge import`, `forge audit` and `forge build --scan`.
