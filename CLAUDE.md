# CLAUDE.md

Project context for Claude Code. Read this before making changes.

## What this is

Bahi — an offline-first personal finance tracker for Android. It is a **portfolio project**: the code is read by hiring managers, so clarity and correctness matter more than feature count. A reviewer should be able to open any file and understand why it is written the way it is.

Current state: **M4b complete**. M0 scaffolding, M1 transactions, M2 CSV import, M3 budgets and rule-based auto-categorisation, M4a row-level sync (engine, resolver, two-device convergence suite, tombstone horizon), M4b the Drive transport (OAuth, end-to-end encryption, compaction, the periodic worker that gives `SyncEngine` its first real caller) and the Settings screen that surfaces a real conflict and restores a discarded value. M5 insights (`:feature:insights`) also shipped; baseline profile and Macrobenchmark are the only pieces of M5 still open. See the Roadmap in README.md. Sync itself has only ever run against an in-memory fake Drive — no real Drive account has exercised this app (`docs/sync-design.md` §13 slice 9h, `docs/sync-setup.md`).

## Stack

Kotlin, Jetpack Compose, Room, Hilt, WorkManager, Coroutines/Flow. Single-activity architecture. 15 Gradle modules with convention plugins in `build-logic/`.

## Architecture rules — these are hard constraints

1. **Features never depend on other features.** `./gradlew checkModuleBoundaries` enforces this and CI runs it. Shared code goes in a `:core` module.
2. **`:core:model` and `:core:common` are pure Kotlin JVM modules.** Never add an Android dependency to them. If something needs `android.*`, it belongs elsewhere.
3. **Room entities never leave `:core:data`.** Features consume domain models from `:core:model` via repository interfaces. Mapping lives in `TransactionMappers.kt`.
4. **Features talk to repositories, never to DAOs.** `:core:database` is not on a feature's dependency list and must not be added to one.
5. **Money is `Money` (value class over `Long` minor units). Never `Double`, never `Float`, never `BigDecimal` in the domain model.** Floating point in currency is a correctness bug.
6. **No `fallbackToDestructiveMigration()`.** Ever. A schema change requires: the migration in `Migrations.ALL`, a test in `MigrationTest`, and the exported schema JSON committed — all in the same commit. When a `MigrationTest` fails, read the report under `core/database/build/reports/androidTests/` rather than the console, which prints only `Migration didn't properly handle: <table>`. Note that Room 2.7.2's `TableInfo.toString()` renders every nested column list as `columns = {kotlin.Unit` in both Expected and Found, so table, index and foreign-key *names* are diffable but a mismatch in index column *ordering* is not — check that case against the exported schema JSON directly.
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
- **Prefer `containsExactly` over `isEqualTo` for collections.** `assertThat(Any)` resolves to the generic `Subject` overload, which happily accepts a collection compared against a scalar -- so a return type widening from `Int` to `List<String>` still compiles and only fails at runtime, if a test happens to cover it. `containsExactly` won't type-check against a non-collection, and it asserts contents rather than identity, so it's both stronger and harder to hold wrong.
- Every new public behaviour in `:core:model` or `:core:data` gets a unit test in the same commit.
- **A measurement over N runs is a sample, not a property.** If a conclusion about ordering, timing or concurrency is going into a code comment or a design doc as a finding, it needs either enough runs to be a real distribution or an assertion that holds regardless of ordering. State the run count wherever the claim appears. `BudgetTotalsTransientTest` is the cautionary case: nine runs of a two-way race all fell the same way, and "the transient can only ever overstate, never lose money" went into the repository comment, the design doc and a test assertion as fact. It was wrong in both directions and failed about half the time once CI ran it on every push.
- Test names: JVM unit tests under `test/` use backticked names with spaces, e.g. `` `emits empty when repository has no transactions` ``. Instrumented tests under `androidTest/` use `lowerCamelCase_withUnderscores` -- DEX rejects method names containing spaces below API 30, and `minSdk` is 26.

## Build

```bash
./gradlew assembleDebug            # compile
./gradlew unitTests                # unit tests, including pure-JVM modules (:core:model, :core:common)
./gradlew checkModuleBoundaries    # architecture check
./gradlew moduleGraph              # regenerate README diagram
./gradlew lintDebug
```

CI compiles with `-PwarningsAsErrors=true`. Before pushing, run `./gradlew clean assembleDebug unitTests -PwarningsAsErrors=true` — a warning that is harmless locally fails the build on CI.

Dependencies are declared **only** in `gradle/libs.versions.toml`. Never inline a version in a module build file. Shared build config goes in a convention plugin in `build-logic/`, never copy-pasted across modules.

Run Gradle quietly and read only what failed: ./gradlew <tasks> --quiet 2>&1 | tail -60. On failure, read the report file rather than scrolling the console output.

## Working style

- **Ask before adding any new third-party dependency.** The dependency list is deliberately small.
- **Ask before changing the module structure.** The graph is the centerpiece of the README.
- Prefer editing existing files over creating new ones.
- Comments explain *why*, not *what*. The existing comments are the house style — match them.
- Do not add a comment restating what a line obviously does.
- Small commits, conventional format: `feat(transactions): add category picker`.
- **If a change alters user-visible copy or layout on a screen that has a committed screenshot, regenerate that screenshot in the same commit.** Nothing checks screenshots — no test fails, no CI job compares them — so a stale one drifts silently and is only caught when someone happens to look. The import result screenshot showed `0 new transaction(s)` for weeks after the string became a proper plural. Check `docs/screenshots/` for the affected screen before you consider the change finished, and regenerate the whole set for that screen together, so the only difference between the images is the one you meant to make.
- When a task is ambiguous, say so and ask rather than guessing.

## Things that are intentionally not done yet

`:core:importer` (M2) and `:core:sync` (M4a and M4b both) are fully implemented now — this note is stale as of M4b and left here only so its history is visible; do not treat any of them as a stub. What is genuinely not built: the baseline profile and Macrobenchmark startup numbers named for M5, and a real Drive account has never exercised sync end to end (everything is proven against an in-memory fake Drive — `docs/sync-design.md` §10.5, §13 slice 9h). Running `docs/sync-setup.md`'s manual plan against a real Google Cloud project would close that gap; nothing else in M4b is waiting on it.

# Build speed. 
- Don't run ./gradlew clean unless you suspect stale state .
- it rebuilds all 16 modules. During iteration, run module-scoped tests (:feature:x:testDebugUnitTest). Run the full gate.
- assembleDebug unitTests checkModuleBoundaries lintDebug -PwarningsAsErrors=true 
- once, at the end of a task, not after each individual change. checkModuleBoundaries discards the configuration cache, so including it forces a reconfigure on the next build.
