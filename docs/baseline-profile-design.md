# Baseline profile and startup benchmark — design

M6. Covers where the measurement comes from, what state the app is in when it
is measured, what the profile contains, how the build and boundary check
learn about a `com.android.test` module, and how each piece is shown to fail.
Short on purpose: there are few decisions here, but each one decides whether a
number in the README means anything.

Related reading: `app/src/main/kotlin/.../BahiApplication.kt` (the work done
before the first frame), `build-logic/.../AndroidApplicationConventionPlugin.kt`
(release is already R8-minified and debug-signed), root `build.gradle.kts`
(`moduleEdges`, `checkModuleBoundaries`).

---

## 1. Where the numbers come from

**Decision: the headline numbers come from a physical device, run by hand. CI
compiles the benchmark module and generates the profile, but publishes no
timings.**

Target device: Realme GT Master Edition (Snapdragon 778G). Android version and
API level are read with `adb shell getprop ro.build.version.release` and
`ro.build.version.sdk` at measurement time and recorded next to the results,
not assumed here.

Why not CI numbers: the instrumented job runs an API 34 x86 emulator under KVM
on a shared runner. Macrobenchmark refuses emulators unless the error is
suppressed, and it is right to — dex2oat, I/O and scheduling there say little
about a phone. A CI number would be a number, not evidence.

Reporting rule (per `CLAUDE.md`: a measurement over N runs is a sample, not a
property). Every figure in the README states the device, the API level, the
compilation mode, **N**, and the **min / median / max** (not only the median).
A delta between two modes is only reported if the ranges do not overlap; if
they do, the README says so instead of quoting a delta. N is at least 10
iterations per mode, more if the spread is wide.

## 2. What state the app is in when measured

**Decision: measure both an empty database and a populated one, and label
them.**

The release build type does not seed transactions (`DebugSeeder` is a no-op in
`src/release`), so a fresh install starts empty — a state no real user's
second launch is in. Cold start on a populated database also exercises the
Room read, the list's first composition and the budgets query.

- **Empty**: install, launch, measure. No extra code.
- **Populated**: seed through the app's own public surface (the CSV importer
  with a fixture file driven by UiAutomator, or the repository layer if the
  benchmark can reach it without a new seam). **The seeding path lives in
  `:benchmark`, not in `:app`.** If it turns out to need a production code
  hook, that is a decision to bring back, not to make quietly.

Both states are reported; the headline is the populated one.

**Seeding happens once, before measurement begins, outside the measured
block.** `StartupMode.COLD` kills the process but does not clear app data, and
Macrobenchmark does not discard the first iteration. Seeding inside the measured
block would therefore make iteration 1 pay for the import and iterations 2..N
not, and the min / max in §1's reporting rule would be measuring that
asymmetry. The benchmark also asserts the expected row count before the first
measured iteration, rather than assuming the seed took: a silently empty
"populated" run is the failure this guards against.

## 3. What the profile contains

The generator runs, in order: cold start to the first frame; the transactions
list scrolled once; navigation to budgets; navigation to insights. Settings and
the import flow are left out — they are rare paths, and every rule in the
profile costs install-time work.

The benchmark itself is cold start only (`StartupTimingMetric`,
`startupMode = COLD`), compared across `CompilationMode.None()` (the
worst case: nothing precompiled), `CompilationMode.Partial(baselineProfileMode
= BaselineProfileMode.Require, warmupIterations = 0)` with the profile, and
`CompilationMode.Full()` as a ceiling. The README's claim is the
`None` → `Partial` delta; `Full` is there to show how much is left.

Both `Partial` arguments are pinned rather than left to defaults. Read from the
`androidx.benchmark:benchmark-macro:1.5.0` sources jar (the release chosen in
slice 1), not `androidx-main`: the constructor is
`Partial(baselineProfileMode: BaselineProfileMode = Require, warmupIterations:
Int = 0)` (`CompilationMode.kt:294-311`), so the defaults are already `Require`
and `0`. Pinning changes nothing today; it keeps the measurement from changing
silently if a default moves.

- **`Require`**, because `UseIfAvailable` succeeds when no profile exists. Read
  from the same sources: `Partial.compileImpl` calls
  `ProfileInstallBroadcast.installProfile`, which returns "No baseline profile
  was found in the target apk." for `RESULT_BASELINE_PROFILE_NOT_FOUND`
  (`ProfileInstallBroadcast.kt:86`). `Require` throws that string
  (`CompilationMode.kt:346-347`); `UseIfAvailable` only logs it and falls
  through (`:349`). With `warmupIterations = 0` nothing is then compiled, so the
  run proceeds on whatever the install left, and reports a number. (The earlier
  version of this claim came from a chat message; it is now confirmed in code.
  A missing `profileinstaller` receiver takes the same log-or-throw path;
  `RESULT_ALREADY_INSTALLED` and the I/O and parse failures throw in both
  modes.) A check that falls back to "no profile" and still produces a number
  is a check that cannot fail (§6).
- **`warmupIterations = 0`**, because a warmed run measures JIT warmup plus the
  profile and attributes the sum to the profile. Cold start is the claim; a
  warmed `Partial` is a different, flattering experiment.

### Generator database state

**Decision: the generator runs against the same populated state as the
populated benchmark, seeded the same way (§2).** The generator scrolls the
list and visits budgets and insights; against an empty database none of those
compose anything, so the profile would carry no rules for the code the
populated measurement — the headline — exercises. Reusing the §2 seeding also
means there is one seeding path to prove, not two.

If that turns out to be impractical (for example the seeding path cannot run
under the plugin's generator), the alternative is a profile generated on an
empty database. It costs the headline: the populated number would then measure
code the profile never saw, and the README would have to say the profile covers
the empty-state path only.

### Which metric

`StartupTimingMetric` reports time to initial display (TTID). That is the wrong
single number here: `TransactionsUiState` starts as `Loading`
(`TransactionsViewModel.kt:119`) and leaves it only when the combine over two
Room flows first emits, so TTID lands on the `Loading` frame, before the Room
read and list composition the populated state was chosen to exercise.

**Decision: report both TTID and time to full display (TTFD).** The transactions
screen calls `ReportDrawnWhen` keyed to its UI state no longer being `Loading`;
`StartupTimingMetric` then emits `timeToFullDisplayMs` alongside
`timeToInitialDisplayMs`. Nothing in the repo calls `reportFullyDrawn()` or
`ReportDrawnWhen` today (searched `app/`, `feature/`, `core/`: not found).

This is a production source change, and it corrects a first assumption that it
sits in `:app`: the screen is `TransactionsRoute` in `:feature:transactions`
(`TransactionsScreen.kt:67`), and that module does not have
`androidx.activity:activity-compose` (`ReportDrawnWhen`'s home) on its
dependency list; only `:feature:settings` does. The change adds the existing
catalog entry `libs.androidx.activity.compose` to `:feature:transactions` — no
new third-party dependency, no module-structure change — and **slice 2 owns
it**, since the first benchmark run already needs TTFD to mean something. The
headline is TTFD; TTID is reported beside it so a reader can see the gap.

### What else is inside the measured window

`BahiApplication.onCreate` calls `syncScheduler.schedulePeriodic()`
(`BahiApplication.kt:54`, WorkManager initialisation on the startup path) and
`MainActivity.onStart` calls `syncScheduler.requestExpeditedSync()`
(`MainActivity.kt:40`). Both land inside cold start, and neither is a
baseline-profile effect.

**Decision: they stay in the number, and the number is the app's real cold
start.** Disabling sync scheduling for the benchmark would need a production
seam and would measure an app that does not ship. The consequence is stated
rather than hidden: the `None` → `Partial` delta must not be credited for them
(they are present in both modes, so they cancel in the delta, but they inflate
the absolute figures), and the README says the absolute times include sync
scheduling. A system trace of one iteration per mode is attached to the
measurement report so the share is visible rather than asserted.

## 4. Profile storage and the module

**Decision: the generated `baseline-prof.txt` is committed under
`app/src/main/`.** It is then reviewable in a diff, normal builds need no
device, and CI can check it is non-empty and in the APK. The cost is that it
goes stale; `docs/` and the module README state when to regenerate (after a
change to startup or to a top-level screen).

Release has `isMinifyEnabled` and `isShrinkResources` on
(`AndroidApplicationConventionPlugin.kt:33-34`), so R8 mapping drift is a
possible third staleness source on top of startup and screen changes. This
document does not assert either way: the expectation is that the committed
file holds unobfuscated names and R8 rewrites them against the build's own
mapping, making drift a non-issue, but that is not established here. It is an
open question for slice 1 (§8).

**One module, `:benchmark`**, applying `com.android.test`, with
`targetProjectPath = ":app"`. It hosts both the profile generator and the
startup benchmark. This takes the graph from sixteen to seventeen modules. A
`benchmark` build type in `:app` initialises from `release`, is not
debuggable, and keeps the R8 and signing configuration already there; it must
not diverge from `release` beyond what Macrobenchmark requires, or the numbers
are for a different app than the one that ships.

New dependencies (asked about and approved by the owner in principle; versions
are chosen and pinned in the slice, in `libs.versions.toml` only):
`androidx.benchmark:benchmark-macro-junit4`, `androidx.test.uiautomator`, the
`androidx.baselineprofile` Gradle plugin, and `androidx.profileinstaller`.

`profileinstaller` 1.4.0 is already on `releaseRuntimeClasspath` transitively,
via activity, compose-ui, lifecycle-runtime and navigation-common (resolved in
the pre-design audit). Decision: **declare it explicitly in
`libs.versions.toml` and in `:app` anyway**, because §6's `ProfileVerifier`
check asserts on it and a test that depends on a transitive breaks when an
unrelated library drops it. This pins the version the check runs against; it
adds no new artifact to the APK.

New convention plugin: `bahi.android.test` in `build-logic/`, so the new module
does not copy-paste SDK and Kotlin configuration.

## 5. The boundary check must see the new module

This is the one place where the existing build is wrong for the new module,
and it is wrong silently.

`moduleEdges()` reads only the `implementation`, `api` and `compileOnly`
configurations for `ProjectDependency` entries. A `com.android.test` module
links to its target through `targetProjectPath`, which creates no
`ProjectDependency`. So `:benchmark` would contribute zero edges, and because
the graph's node list is derived from the edge list, it would also be missing
from `moduleGraph` and the README diagram — while `checkModuleBoundaries` kept
printing "Module boundaries OK".

Required in the same slice as the module:

1. `moduleEdges()` also emits `:benchmark -> :app` from `targetProjectPath`
   (the edge is real, so it belongs in the graph), and `tierOf` gets a case
   for it instead of falling into `other`.
2. The module is shown to be counted: the edge total printed by
   `checkModuleBoundaries` rises by exactly that edge, and `:benchmark` appears
   in the generated graph.
3. The check is shown to be able to fail on this module: temporarily add a
   `:feature:*` dependency to `:benchmark`'s `implementation` and a rule
   forbidding it. **Open point:** today's rule only forbids feature→feature.
   The slice states what `:benchmark` may depend on (`:app` only) and adds
   that as an enforced rule, otherwise "the check handles the module" proves
   nothing about it.
4. The hardcoded counts are updated in the same commit: the README's
   "sixteen" mentions (line 54 and line 126, the convention-plugin paragraph),
   the "15 Gradle modules" line in the Stack section of `CLAUDE.md` (line 13)
   and the 16-module count in its build-speed note (line 77), the node and edge
   counts in the `moduleGraph` comment (root `build.gradle.kts:55-58`, and the
   "15+ lines" at `:128`), `KotlinAndroid.kt:15` ("15 modules"),
   `gradle.properties:3` ("15-module build"), and the README diagram. Nothing
   checks any of these, so they drift silently — the same failure mode
   `CLAUDE.md` records for screenshots, and the reason the list is
   enumerated here instead of trusted to a search at edit time. (The "fifteen
   minutes" at `docs/sync-design.md:1965` is not a module count.)

5. **The instrumented CI job must not run the benchmark.** The reverse of the
   problem above: `ci.yml`'s `instrumented` job runs root-level
   `./gradlew connectedDebugAndroidTest` on an API 34 x86_64 emulator
   (`ci.yml:108-112`). Depending on which variants AGP registers for a
   `com.android.test` module, that task name may select `:benchmark`, which
   would either fail CI on the emulator check or invite someone to silence it
   with `androidx.benchmark.suppressErrors`. The exclusion is therefore
   deliberate and verified: slice 5 scopes the job and the CI log is observed
   not running the benchmark. **`androidx.benchmark.suppressErrors` is never
   set in this repo**, in CI or locally.

`unitTests` needs no change: it only registers for application, library and
JVM-library plugin ids, and `com.android.test` has none.

## 6. How each piece is shown to fail

Nothing here is trusted until it has been seen failing.

| Check | Deliberate break | Expected |
|---|---|---|
| Boundary check counts `:benchmark` | Revert the `targetProjectPath` edge | Edge count drops by one; `:benchmark` vanishes from the graph |
| Boundary rule for `:benchmark` | Add `projects.feature.budgets` to it | `checkModuleBoundaries` fails and names the edge |
| Profile is in the APK | Delete `baseline-prof.txt`, rebuild release | `assets/dexopt/baseline.prof` absent from the APK; CI step fails |
| Profile is not empty | Truncate it to zero rules | CI step fails on a size/rule-count floor |
| Benchmark target is release-like | Point it at the debuggable debug build | Macrobenchmark refuses to run (the `DEBUGGABLE` error), not a quiet slow number |
| Profile helps at all | Remove the profile, run `Partial(Require, warmupIterations = 0)` | The run **fails** (`Require` finds no profile) instead of producing a number |
| Profile is actually applied (authoritative) | Instrumented assertion on `ProfileVerifier.getCompilationStatusAsync()`: a compiled profile exists. Break: remove the profile, install | The assertion fails, naming the status (no profile / not compiled) |
| Profile is actually applied (cross-check) | `adb shell dumpsys package dexopt` after install | Compiler filter / reason reflects the profile, not `verify` |

The previous formulation of the "helps at all" row — compare `None` against
`Partial` with the profile removed and expect overlapping ranges — was wrong,
and wrong in the direction that manufactures a positive result: with warmup on,
a profile-less `Partial` still warms up and so will not match `None`, and that
real gap would read as evidence the profile works when the profile is absent.
Under `Require` and zero warmup the question has a sharper answer: no profile
means no number.

`ProfileVerifier` is authoritative because it is an assertion that fails a
run; `dumpsys` is a manual eyeball and is kept only as a cross-check.

The last three matter most for the README's claim: they distinguish "a profile
exists" from "a profile made startup faster".

## 7. Slices

1. **Module, plugin, dependencies, boundary check.** `:benchmark` exists and
   builds; §5 in full, including the deliberate failures. README module count
   and diagram updated. The `benchmark` build type in `:app` also lands here,
   since the module needs a target to build against. Owns the M5 → M6 rename
   of the open item in `CLAUDE.md` (the README roadmap already said M6). No
   benchmark code yet.
2. **First run.** `StartupBenchmark` with `CompilationMode.None()` on the empty state,
   confirmed to run on the device and to refuse the debug build. Also owns the
   `ReportDrawnWhen` change in `:feature:transactions` (§3, "Which metric"), so
   the first run already reports TTID and TTFD.
3. **Profile generation.** The generator (on the populated state, §3), the
   committed profile, the APK and non-empty checks, the `ProfileVerifier`
   assertion with its deliberate break, `dumpsys` as cross-check.
4. **Measurement.** Empty and populated states, `None` / `Partial` / `Full`,
   N and spread recorded in this document and the README; the README M6 item
   ticked only if the numbers support the claim.
5. **CI.** Compile `:benchmark` and the profile checks on every push; a
   manual-dispatch workflow for generation. No timings in CI. Also the
   `instrumented` job exclusion of `:benchmark` (§5 item 5), verified from the
   CI log.

## 8. Not decided here

- Exact library and plugin versions: chosen in slice 1 and recorded in the
  report.
- Whether populated-state seeding needs a production seam (§2): known only
  once slice 4 starts. Because the generator shares that seeding (§3), it is
  now also needed by slice 3; slice 3 starts the answer.
- Whether R8 mapping drift makes a committed profile stale invisibly (§4).
  Slice 1 establishes it from the plugin's behaviour; unresolved here.
- The `androidx.benchmark` version, and therefore a re-check of the
  `CompilationMode.Partial` defaults against that release (§3, checked only
  against `androidx-main`).
- Whether the result is "no measurable difference". That is a possible
  outcome of a real measurement; if it happens it goes in the README as it is.
