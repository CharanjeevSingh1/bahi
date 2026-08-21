# CLAUDE.md

Project context for Claude Code. Read this before making changes.

## What this is

Bahi — an offline-first personal finance tracker for Android. It is a **portfolio project**: the code is read by hiring managers, so clarity and correctness matter more than feature count. A reviewer should be able to open any file and understand why it is written the way it is.

Current state: **M0 complete** (scaffolding). See the Roadmap in README.md.

## Stack

Kotlin, Jetpack Compose, Room, Hilt, WorkManager, Coroutines/Flow. Single-activity architecture. 15 Gradle modules with convention plugins in `build-logic/`.

## Architecture rules — these are hard constraints

1. **Features never depend on other features.** `./gradlew checkModuleBoundaries` enforces this and CI runs it. Shared code goes in a `:core` module.
2. **`:core:model` and `:core:common` are pure Kotlin JVM modules.** Never add an Android dependency to them. If something needs `android.*`, it belongs elsewhere.
3. **Room entities never leave `:core:data`.** Features consume domain models from `:core:model` via repository interfaces. Mapping lives in `TransactionMappers.kt`.
4. **Features talk to repositories, never to DAOs.** `:core:database` is not on a feature's dependency list and must not be added to one.
5. **Money is `Money` (value class over `Long` minor units). Never `Double`, never `Float`, never `BigDecimal` in the domain model.** Floating point in currency is a correctness bug.
6. **No `fallbackToDestructiveMigration()`.** Ever. A schema change requires: the migration in `Migrations.ALL`, a test in `MigrationTest`, and the exported schema JSON committed — all in the same commit.
7. **Soft deletes only.** Set `deleted_at` and a pending `DELETE` operation. Sync needs the tombstone.
8. **Dispatchers are injected**, never referenced directly. Use `@Dispatcher(BahiDispatcher.IO)`.

## Compose conventions

- Every screen is a pair: a stateful `XRoute()` that owns the ViewModel, and a stateless `internal XScreen(uiState, onEvent)` that is previewable and testable.
- UI state is a **sealed interface**, not a data class with nullable fields. `Loading` / `Empty` / `Success` / `Error` must be distinguishable.
- Lists take `ImmutableList` from kotlinx-collections-immutable, not `List` — it keeps composables stable.
- `collectAsStateWithLifecycle()`, never `collectAsState()`.
- Test tags live in an `internal object XTestTags` next to the screen.

## Testing conventions

- **Fakes, not mocks.** No MockK, no Mockito. Hand-write a fake implementing the repository interface. A mock verifies a call happened; a fake lets you assert on behaviour.
- ViewModel tests use `MainDispatcherRule` + Turbine.
- Assertions use Truth (`assertThat`).
- Every new public behaviour in `:core:model` or `:core:data` gets a unit test in the same commit.
- Test names use backticks and describe behaviour: `` `emits empty when repository has no transactions` ``.

## Build

```bash
./gradlew assembleDebug            # compile
./gradlew unitTests                # unit tests, including pure-JVM modules (:core:model, :core:common)
./gradlew checkModuleBoundaries    # architecture check
./gradlew moduleGraph              # regenerate README diagram
./gradlew lintDebug
```

Dependencies are declared **only** in `gradle/libs.versions.toml`. Never inline a version in a module build file. Shared build config goes in a convention plugin in `build-logic/`, never copy-pasted across modules.

## Working style

- **Ask before adding any new third-party dependency.** The dependency list is deliberately small.
- **Ask before changing the module structure.** The graph is the centerpiece of the README.
- Prefer editing existing files over creating new ones.
- Comments explain *why*, not *what*. The existing comments are the house style — match them.
- Do not add a comment restating what a line obviously does.
- Small commits, conventional format: `feat(transactions): add category picker`.
- When a task is ambiguous, say so and ask rather than guessing.

## Things that are intentionally not done yet

`:core:importer` and `:core:sync` contain interfaces only. Those are M2 and M4. Do not implement them unless the task explicitly says to.
