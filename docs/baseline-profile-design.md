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
  hook, that is a decision to bring back, not to make quietly. *Answered in
  slice 3: no hook is needed. See "Seeding, as built" below.*

Both states are reported; the headline is the populated one.

**Seeding happens once, before measurement begins, outside the measured
block.** `StartupMode.COLD` kills the process but does not clear app data, and
Macrobenchmark does not discard the first iteration. Seeding inside the measured
block would therefore make iteration 1 pay for the import and iterations 2..N
not, and the min / max in §1's reporting rule would be measuring that
asymmetry. The benchmark also asserts the expected row count before the first
measured iteration, rather than assuming the seed took: a silently empty
"populated" run is the failure this guards against.

*Slice 3 kept the requirement and had to change the mechanism: on the
measurement device "once, before" is not achievable, because the library wipes
the database after any seed that runs before it. See below.*

### Seeding, as built (slice 3)

**No production seam is needed.** `:benchmark/PopulatedState.kt` drives the
app's own CSV importer through the system file picker with UiAutomator. Nothing
in `:app` or a feature module changed for it.

How it works, and the three things that did not work the first time:

- The fixture is a 300-row CSV generated in `:benchmark` (three rows a day
  ending today, so the current month has data for budgets and insights; seeded
  `Random`, so it is the same file every run). It is written to Downloads with
  `MediaStore.Downloads`, which needs no storage permission on API 29+.
- **A file placed with `adb push` is invisible to the picker.** It is not
  MediaStore-indexed; the picker opens on Recent, lists nothing of it, and
  searching Downloads answers "Can't load content". Hence MediaStore.
- **The file name is unique per run.** MediaStore renames an insert that
  collides with a file already on disk ("name (1).csv"), and the test then
  waited for text that was not on screen. A stale file from a manual probe
  caused exactly this. The fixture is deleted when seeding finishes.
- Controls are found by content description or text. No
  `testTagsAsResourceId` is set anywhere in the app and none was added.
- The row count is asserted exactly, on three screens: the preview says "300
  transactions found", the result says "300 new transactions", and the list then
  shows an imported row. The assertion can be exact because seeding runs
  `pm clear` first.

**`collect()` reinstalls the target, and a reinstall wipes the database.**
This is why the seed cannot run before collection. On the measurement device
(API 33, not rooted) `CompilationMode.resetAndCompile` takes its "User builds
pre-U" branch, `reinstallPackage`, a full uninstall and install
(`CompilationMode.kt:68-135`, benchmark-macro 1.5.0 sources). `collect` calls
it on its first iteration only (`BaselineProfiles.kt:76`). Observed on the
device: a seed run before `collect` completed with 300 rows, and the list inside
`collect` showed "No transactions yet".

So the generator seeds **inside iteration 1, after the reinstall**, kills the
process, and then runs the journey; iterations 2.. reuse the database. This puts
the import flow in the target process's profile. `app/build.gradle.kts` filters
`feature.csvimport.**` and `core.importer.**` back out. Two things remain in the
committed profile because a class filter cannot separate them from code the
journey legitimately uses: `OfflineFirstTransactionRepository.importAll` and
`ImportBatchResult` in `:core:data`, and the Hilt component's
`androidCsvFileReader()` accessor. These are rules for code the generator ran
only to seed. They cost a few install-time entries, not correctness.

**For slice 4:** `measureRepeated` calls the same `resetAndCompile` (the stack
of the break (c) run shows `macrobenchmark(Macrobenchmark.kt:286)` calling it),
once before its iterations. The wipe was not observed there, only read from the
sources and the trace, but the conclusion is the same: seeding before
`measureRepeated` will be wiped, and the seed belongs in `setupBlock` on the
first iteration, outside the measured block.

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

### Where the profile was generated (slice 3)

**On the Pixel_9_Pro_XL emulator, API 36 (arm64), not on the measurement
device.** The Realme's ROM refuses `cmd package compile` (§8), and `collect()`
needs it. The measurement device runs Android 13 (API 33). Whether a profile
generated on API 36 helps an API 33 install the way one generated there would is
not established here; ART's profile format and the app's dex are the same, but
that is a reason to expect it, not evidence. Slice 4 has to say whether the
mismatch matters.

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
(`TransactionsScreen.kt:67`), and that module does not declare
`androidx.activity:activity-compose` (`ReportDrawnWhen`'s home); only
`:feature:settings` does. (It is reachable transitively through
`navigation-compose`, which is why `TransactionFormScreen` already imports
`BackHandler` from it; slice 2 declared it explicitly anyway. See §9.) The change adds the existing
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
possible third staleness source on top of startup and screen changes.

**R8, answered (slice 3).** The committed file holds unobfuscated names and R8
rewrites them against the build's own mapping, so drift in the mapping itself is
a non-issue. Measured on the `benchmark` variant, three different counts, each
of a different thing:

- **47,218**: lines in the committed `baseline-prof.txt`, 1,495 of them this
  app's. The same line count before and after the profile was regenerated.
- **47,602**: lines of the merged profile AGP hands to R8
  (`expandBenchmarkArtProfileWildcards`). That is the committed file plus the
  rules AndroidX libraries ship inside their own AARs (384 lines not in the
  committed file; none of the committed file's lines are missing). It was 47,600
  when first measured, before regeneration. The regeneration was not
  byte-identical (about a dozen library rules swapped, 24 lines in a set
  difference; the same 1,495 app rules), which is the two-line shift.
  **The committed profile has a small non-deterministic component.** A second
  generation, same journey and same seeded fixture, produced 24 differing
  library-rule lines against the first and an APK `baseline.prof` seven bytes
  smaller (6,555 against 6,548), with identical line and app-rule counts. After
  regenerating, a `diff` is therefore expected to show some churn in AndroidX
  and Kotlin lines that is not a real change; judge a regeneration by its app
  rules, not by whether the diff is empty. (One repeat; the size of the
  non-determinism is not characterised.)
- **12,954**: lines R8 writes out (`minifyBenchmarkWithR8`), the same in both
  measurements, with this app's classes renamed (`Ly1/f1;`, not
  `Ldev/charanjeev/bahi/...`). The shrink is the point: most of the file is
  AndroidX and Kotlin rules for classes the app does not keep.
The failure to know about is the quiet one. **A rule whose class or method no
longer exists is dropped silently**: nothing errors, the profile just covers
less. A profile left behind by a rename or a deleted screen therefore degrades
without a signal. `checkBaselineProfile`'s app-rule floor (§6) catches the
gross version of this, a profile gutted down to library rules, and does not
catch a profile that is merely stale by a few hundred rules. Regenerating after
a startup or top-level-screen change remains the control for that.

**Where the file lives.** `app/src/main/generated/baselineProfiles/baseline-prof.txt`,
the plugin's default output with `mergeIntoMain = true` (observed: the first
generation wrote it there). The plugin wires that directory into the variants it
creates (`release`, `nonMinifiedRelease`, `benchmarkRelease`) and not into the
custom `benchmark` build type, so `app/build.gradle.kts` adds it to that build
type's source set by hand
(`sourceSets.getByName("benchmark").baselineProfiles.srcDir(...)`). Without that
line the benchmark APK carries only library profiles (next section). It is 5.3 MB
of text, almost all of it library rules.

### Moving the profile to `src/main` broke `assembleRelease` (slice 3)

A second near-miss, found a round after the first, and caused by the fix for it.

`baselineProfileOutputDir = "."` was added to `app/build.gradle.kts` to remove a
trap. With the plugin's default location, regenerating the profile writes it
where the `benchmark` build type does not read it, so a future regeneration
would silently reproduce the first near-miss. Setting the output directory to
`.` writes the file to `src/main/baseline-prof.txt`, which AGP reads for every
variant with no wiring. It was checked by regenerating on the emulator: a file
with the same 47,218 lines and 1,495 app rules landed in `src/main/` and nothing
was written under `generated/`, and the claim at the time was that this
*proved* the location.

That proof was sound for what it tested, which was that the profile landed in the
right file. It was silent about what else now counted as a profile source. The
mechanism, inferred from the merged output and not read from the plugin's source:
for the variants it wires itself, the plugin appears to hand AGP the output
directory rather than the file. With the directory set to `.` that is
`src/main/`, and the release variant's `mergeReleaseArtProfile` took what was
under it as profile input. Its merged output ran to 50,580 lines (the benchmark
variant's, which reads only the one file, ran to 50,000), with the text of a
launcher-icon vector drawable from `res/` (the file name was not checked)
starting at line 2,783, and `expandReleaseArtProfileWildcards` rejected it:

```
Error parsing baseline-prof.txt : baseline-prof.txt:2783:1 error: Illegal token '<'
  <?xml version="1.0" encoding="utf-8"?>
```

So `assembleRelease` was broken, and CI builds release.

It went unnoticed because nothing in the loop builds release. The project gate
(`assembleDebug unitTests checkModuleBoundaries lintDebug`) is debug-only, and
`checkBaselineProfile` builds only the `benchmark` variant, which was fine
precisely because it does not use the plugin's wiring. It was found by a
Scenario A run for an unrelated check (§6, TTFD), when a `:benchmark` connected
task pulled in `:app:expandReleaseArtProfileWildcards` and failed. No check
caught it.

The fix: reverted to the plugin's default location
(`src/main/generated/baselineProfiles/`, a directory that holds only the
profile), and the `benchmark` source set is wired to it by hand with
`sourceSets.getByName("benchmark").baselineProfiles.srcDir(...)`. Verified on
both variants: `assembleRelease` builds and its merged profile carries the same
1,495 app rules; deleting the committed file makes `mergeBenchmarkArtProfile`
re-run and the app-rule floor fail; restoring it passes. The trap the setting
was meant to remove stays removed by the source-set line, not by relocating the
file.

Stated as a rule: **a proof of one property is not a proof that nothing else
changed, and a change to build wiring is verified on every variant the build
produces, not on the one the change was aimed at.** The gate was the other half
of the miss: it did not build release. `CLAUDE.md`'s Build section now adds
`assembleRelease` to it for the duration of M6, since M6 touches R8, profile
merging and a new build type, all release-side, and CI builds release.

### The first APK check passed falsely (slice 3)

The first version of `checkBaselineProfile` read the committed profile and the
APK's `assets/dexopt/baseline.prof`, and reported green. It was green over an
APK that contained none of this app's rules.

The mechanism, in the order it happened:

1. The plugin wrote the generated profile to
   `src/main/generated/baselineProfiles/baseline-prof.txt`. That directory is
   wired into the variants the plugin creates (`nonMinifiedRelease`,
   `benchmarkRelease`). It is **not** wired into the custom `benchmark` build
   type this project measures.
2. AGP's `mergeBenchmarkArtProfile` therefore saw no app profile for that
   variant. It still produced `assets/dexopt/baseline.prof`, because AndroidX
   libraries (Compose, lifecycle, profileinstaller and others) ship profiles
   inside their AARs and AGP merges them in.
3. The check asked "does the committed file have app rules" (yes, it did, in
   `src/`) and "is the APK's profile present and over a size floor" (yes, from
   the libraries). Neither question was about what the APK was built from.

It was found by running break (a), the deliberate deletion. The first run of
it "failed", but on the text-file floor, so it said nothing about the APK. With
the committed file deleted,
`mergeBenchmarkArtProfile` reported `UP-TO-DATE`: deleting the file changed
none of that task's inputs, which is only possible if the variant never read it.
The APK still carried `baseline.prof` at the same 5,307 bytes.

**Size does not separate the two states.** With no app rules the APK's
`baseline.prof` was 5,307 bytes; with this app's 1,495 rules it was 6,555 (6,548
after the profile was regenerated, with the same rule counts: the file is not
byte-stable across generations, so no byte figure here is a constant). A
floor low enough to be safe across library upgrades passes in both states, so
§4's original "non-empty" was never sufficient and is withdrawn. The control
that separates them is a **rule count on the variant's own merged profile**
(`intermediates/r8_art_profile/benchmark/expandBenchmarkArtProfileWildcards/`),
filtered to this app's package. That is what `checkBaselineProfile` does now:
floor 500 app-class rules, against 1,495 actually present; the APK check is
presence only.

Stated as a rule: **a check on a build artifact has to read the artifact the
build produced, not the input the author believes went into it.** The
committed file existing, and the APK containing a profile, were each true and
together said nothing.

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
`libs.versions.toml` and in `:app` anyway**. The reason given in slice 1 was
that §6's `ProfileVerifier` check asserts on it, and a test that depends on a
transitive breaks when an unrelated library drops it. `ProfileVerifier` was
dropped in slice 3 (§6), so that reason is gone. The declaration stays on a
weaker one: `profileinstaller` is the code that installs the profile at first
launch, and pinning it keeps that behaviour from moving with an unrelated
library upgrade. It adds no new artifact to the APK.

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
   `./gradlew connectedDebugAndroidTest` on an API 34 x86_64 emulator, and
   Macrobenchmark refuses emulators. Slice 2 excluded `:benchmark` with
   `-x :benchmark:connectedDebugAndroidTest`, verified with `--dry-run`.
   **Slice 3 found that exclusion broken, and removed it.** Applying the
   baselineprofile plugin removed `:benchmark`'s `debug` variant; its connected
   tasks are now `connectedBenchmarkBenchmarkAndroidTest`,
   `connectedBenchmarkReleaseAndroidTest`,
   `connectedNonMinifiedBenchmarkAndroidTest` and
   `connectedNonMinifiedReleaseAndroidTest`. Gradle does not ignore an
   exclusion that matches nothing; it fails:
   `Cannot locate excluded tasks that match ':benchmark:connectedDebugAndroidTest'
   as task 'connectedDebugAndroidTest' not found in project ':benchmark'`.
   So the guard had gone from "guarding the benchmark" to "failing the whole
   instrumented job". Dry run of the command as it now stands
   (`./gradlew connectedDebugAndroidTest --dry-run`): 14 modules'
   `connectedDebugAndroidTest` selected (`:app` and 13 others), no `:benchmark`
   task. The comment in `ci.yml` says why there is no `-x` and says to re-check
   after any change to `:benchmark`'s build types, since the exclusion's absence
   is now the thing holding. Slice 5 still observes the CI log not running the
   benchmark. **`androidx.benchmark.suppressErrors` is never set in this repo**,
   in CI or locally.

`unitTests` needs no change: it only registers for application, library and
JVM-library plugin ids, and `com.android.test` has none.

## 6. How each piece is shown to fail

Nothing here is trusted until it has been seen failing.

| Check | Deliberate break | Expected | Observed (slice 3) |
|---|---|---|---|
| Boundary check counts `:benchmark` | Revert the `targetProjectPath` edge | Edge count drops by one; `:benchmark` vanishes from the graph | Slice 1 |
| Boundary rule for `:benchmark` | Add `projects.feature.budgets` to it | `checkModuleBoundaries` fails and names the edge | Slice 1 |
| App rules reach the benchmark variant (the control that catches deletion) | (a) Delete `baseline-prof.txt`, rebuild. (b) Truncate it to zero rules | `checkBaselineProfile` fails on the app-rule floor | Both fail: `benchmark variant merged 0 app-class rules, floor is 500`. Restored: 1,495 |
| Profile is in the APK | none constructible | n/a | See below: **cannot fail**, row rewritten |
| Benchmark target is release-like | Point it at the debuggable debug build | Macrobenchmark refuses (`DEBUGGABLE`) | Slice 2 |
| TTFD is reported | `ReportDrawnWhen { false }`, run on the device | `checkBenchmarkData` fails | Test passes; JSON has only `timeToInitialDisplayMs`; check fails naming the benchmark and the missing metric. Reverted: passes |
| Profile is applied (authoritative) | `adb shell dumpsys package dexopt` after install | Compiler filter / reason reflects the profile, not `verify` | **Not exercised.** Needs a profile-compiled install, which the Realme's ROM prevents (§8) |
| `Require` fails with no profile | Remove the profile, run `Partial(Require, warmupIterations = 0)` | The run **fails** because `Require` finds no profile | **Not observed.** See below |

**"Profile absent from the APK" cannot fail, and the row was unfalsifiable as
written.** The previous row said: delete `baseline-prof.txt`, rebuild, and
`assets/dexopt/baseline.prof` is absent. It is not, and cannot be, while any
AndroidX library on the classpath ships a profile in its AAR: AGP merges those
into the same asset. Observed: with the app's profile deleted the APK still had a
5,307-byte `baseline.prof`. A check for presence passes in every state, so it
is kept only as a sanity assertion. **What catches deletion is the app-rule
floor on the variant's merged profile**, which counts rules for this app's
package and goes to 0 when the file goes.

**`Require` and the missing profile (not observed).** The `Partial(Require,
warmupIterations = 0)` break was run on the device with the app's profile removed.
It failed, but not as the row expects:

```
java.lang.IllegalStateException: Failed to compile (out=Error: Failed to cpmpile !
	at androidx.benchmark.macro.CompilationMode$Companion.cmdPackageCompile$benchmark_macro(CompilationMode.kt:437)
	at androidx.benchmark.macro.CompilationMode$Partial.compileImpl$benchmark_macro(CompilationMode.kt:344)
	at androidx.benchmark.macro.CompilationMode.resetAndCompile$benchmark_macro(CompilationMode.kt:126)
```

`compileImpl:344` is inside the branch that runs only after
`ProfileInstallBroadcast.installProfile` returned *success*. `Require` was
satisfied: the library profiles in the APK count as "a baseline profile was
found". So two things are true at once. The row's premise (an APK with no
profile) cannot be built here for the same reason as above, and the device then
fails at the compile step on the ROM's refusal (§8) before any number exists.
The §3 claim that `Require` throws on a missing profile is still read from the
sources and is still correct as far as it goes; what is withdrawn is the idea
that this project can show it. Nothing was suppressed to get past either
failure.

**Why the `Require` row is phrased as it is.** An earlier formulation of "helps
at all" compared `None` against `Partial` with the profile removed and expected
overlapping ranges. That was wrong, and wrong in the direction that manufactures
a positive result: with warmup on, a profile-less `Partial` still warms up and so
will not match `None`, and that real gap would read as evidence the profile
works when the profile is absent. Under `Require` and zero warmup the question
has a sharper answer (no profile, no number), which is the answer slice 3 could
not demonstrate.

**`dumpsys package dexopt` is authoritative; `ProfileVerifier` is dropped.**
This inverts the earlier version, which made the verifier the assertion and
`dumpsys` the manual cross-check. Why:

- `ProfileVerifier` reads the app's own files and so has to run in the app's own
  process. `:benchmark`'s test process is not that.
- The only place an in-process assertion can live is `:app`'s `androidTest`, and
  that runs against `debug`, which packages no profile. Pointing it at a
  non-debuggable variant means changing `testBuildType`, which changes what CI's
  `connectedDebugAndroidTest` selects, and a minified target would then need
  `-keep` rules so the test can find `androidx.profileinstaller`. That is a
  change to **shipped R8 configuration**.
- `dumpsys package dexopt` can be run from `:benchmark` (or by hand) against the
  installed target and reports the compiler filter and reason. It already
  provides the check, for none of that cost.

The old break (d), the verifier with the profile removed, is removed with it.
Be clear about the limit: `dumpsys` was **not exercised for a profile-compiled
install in this slice**, because the only device the measurement runs on cannot
compile. "Authoritative" here is a decision about where the check lives, not a
claim that it has been seen to work.

**TTFD (slice 3).** Slice 2 showed that a `ReportDrawnWhen` that never fires
makes `timeToFullDisplayMs` vanish while the test passes, and that nothing
inside the test can see it (against benchmark-macro 1.5.0, `measureRepeated`
returns `Unit`, `Metric` is sealed, and the one function that returns results is
`@RestrictTo`). `./gradlew :benchmark:checkBenchmarkData` is the check around
the test: it reads every `benchmarkData.json` under
`benchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark/` and fails if
any benchmark lacks `timeToFullDisplayMs`. Run against the real file from a
`ReportDrawnWhen { false }` build:

```
> Benchmark data check failed:
    startupCompilationNone_emptyDatabase: timeToFullDisplayMs is absent (has [timeToInitialDisplayMs])
```

and against the reverted build: `Benchmark data OK: 1 file(s), timeToFullDisplayMs
present in every benchmark`. **The test alone cannot catch this, and the check
around it can. That gap is permanent**: it is a property of the library's API,
not of this repo's code, and it closes only if a later benchmark-macro exposes
the results to the test.

**The JSON check cannot be left to a manual step, and its second version was a
near-miss.** The first version was a separate task that read every
`benchmarkData.json` under the outputs directory, so stale files from earlier
runs counted, and the directory was cleared by hand between runs.

The second looked like the fix and failed its own proof. It deleted the output
directory in the benchmark task's `doFirst`. The proof put a stale file that
*would pass* in place and then ran the benchmark; the build died before the task
started (an `:app` task failed, see §4), so the `doFirst` never ran, the stale
file survived, and `checkBenchmarkData` passed on it. A check that passes on data
from a run that never happened is worse than no check, because it reads as
assurance. The deletion protected only the runs that were going to work.

What stands instead: `connectedBenchmarkBenchmarkAndroidTest` deletes its own
output directory in `doFirst` **and is finalized by** `checkBenchmarkData`, which
reads only that directory. It has no hand-maintained precondition because the
two halves are tied to the same task: there is no step for a person to remember,
and no state a person has to have set up. Every run that reaches the task is
judged on its own data. A run that produces no JSON leaves an empty directory,
which fails the check (it does not skip it). A build that dies before the task
starts never runs the finalizer, so there is nothing to pass on stale data and
the build is red on its own account. Shown on the device with a stale file in
place each time: a stale file that *passes*, plus a run whose filter matched
nothing, ends `No benchmarkData.json under ...: the last
connectedBenchmarkBenchmarkAndroidTest run produced none` (the stale file was
deleted, not read); a stale file that *fails*, plus a good run, ends `Benchmark
data OK: 1 file(s)` with only the fresh file left.

Limits that remain: run on its own, `checkBenchmarkData` judges whatever the last
run left, which is a previous run's by definition. And the cleanup and the
finalizer attach to the task by name, which is itself the kind of coupling that
rots, so a rename fails configuration (`afterEvaluate` check) instead of
silently dropping both.

The "Profile helps at all" and "Profile is actually applied" rows are what
distinguish "a profile exists" from "a profile made startup faster". After this
slice neither has been observed on a device, which is the §8 risk.

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
   the first run already reports TTID and TTFD. Owns the `instrumented` CI job
   exclusion too (§5 item 5): adding benchmark code is what makes the root-level
   `connectedDebugAndroidTest` start selecting a test that cannot run on an
   emulator, so the scoping cannot wait for slice 5. (Superseded in slice 3:
   that exclusion was removed once the plugin made it unnecessary and then
   harmful; §5 item 5.) Results: §9.
3. **Profile generation.** The generator (on the populated state, §3), the
   committed profile, `checkBaselineProfile`, `checkBenchmarkData` (pulled
   forward from slice 4), and the seeding answer (§2). Landed: see §2, §4 and
   §6 for what was built and what was found. `ProfileVerifier` was dropped and
   `dumpsys` made authoritative (§6). Not landed: the `Require` break and any
   observed `dumpsys` result, both blocked on the device (§8). Also fixed the
   `instrumented` CI job's exclusion, which the plugin had broken (§5 item 5).
4. **Measurement.** Empty and populated states, `None` / `Partial` / `Full`,
   N and spread recorded in this document and the README; the README M6 item
   ticked only if the numbers support the claim.
5. **CI.** Compile `:benchmark` and the profile checks on every push; a
   manual-dispatch workflow for generation. No timings in CI. The
   `instrumented` job exclusion landed in slice 2; this slice only confirms it
   from the CI log.

## 8. Not decided here

**The largest open risk in M6: the measurement device refuses `cmd package
compile`.** The Realme RMX3360 (Android 13, API 33, a `user` build) answers
`Error: Failed to cpmpile !` to `cmd package compile` for every mode (`verify`,
`speed`, `speed-profile`) and every package, system apps included, with both
"Disable permission monitoring" and "USB debugging (Security settings)" turned
on and USB reconnected. The shell is the plain `shell` uid. logcat showed
nothing beyond the adb service line. `pm art` is also unknown there.
`CompilationMode.Partial` and `Full` both end in that call, so slice 4's
`None` / `Partial` / `Full` comparison cannot run on this device as things
stand. Slice 2's run worked only because `CompilationMode.None()` never calls
it. The cause is not known. This is recorded as open and is slice 4's to
resolve.

Closed in slice 3:

- **Whether populated-state seeding needs a production seam (§2).** No. The
  importer is driven from `:benchmark`. Recorded in §2, "Seeding, as built",
  including that `collect()` reinstalls the target and wipes the database, so
  the seed runs inside iteration 1, and that `importAll` and `ImportBatchResult`
  stay in the profile because a class filter cannot separate them from the
  shared repository.
- **Whether R8 mapping drift makes a committed profile stale invisibly (§4).**
  Answered in §4: names are rewritten against the build's own mapping (1,495
  app rules in the 47,218-line committed file; 47,602 merged into R8; 12,954
written out, §4), a rule for a class that no
  longer exists is dropped silently, and the app-rule floor catches a profile
  gutted this way but not one that is merely a few hundred rules stale.

Still open:

- **Does generating on API 36 matter for an API 33 measurement?** The profile
  was generated on the Pixel_9_Pro_XL emulator (API 36), and the measurement
  device is API 33 (§3). Slice 4 has to say whether the mismatch matters.
- **Lint stack traces, cause unknown.** `lintDebug` has printed
  `NoSuchFileException` traces against
  `app/build/intermediates/lint-cache/lintVitalAnalyzeRelease/migrated-jars/...RuntimeIssueRegistry...jar`.
  On the slice 3 tree they appeared in 2 of the 8 runs that completed as ordinary
  lint runs (the first full gate and the `lintDebug` that followed it; the other
  six, including the final gate with `assembleRelease` added, printed none). A
  further four `--rerun-tasks` runs crashed differently and printed none; they
  are not counted either way. They were
  absent on clean HEAD. An A/B on `baselineProfileOutputDir` (`clean lintDebug`,
  two runs each way) ruled that setting out. `lintVitalAnalyzeRelease` is not in
  the gate's task graph, with or without the setting (`--dry-run`). Exit code 0
  throughout. The cause is unknown. One idea, **an unproven guess and not a
  cause**: a build-cache restore after `clean` that points at lint-cache jar paths
  which no longer exist. Both traced runs followed builds of the release and
  benchmark variants. Not fixed; no workaround applied.
- **`--rerun-tasks` is not usable as a lint probe.** It crashes lint in
  `:core:designsystem` on a missing detector class
  (`androidx/lifecycle/lint/NonNullableMutableLiveDataDetector$createUastHandler$1`),
  with the setting present or absent, so any trace-hunting with it is measuring
  the crash.
- **`dumpsys package dexopt` has not been seen to show a profile-compiled
  install** (§6), for the same reason as the risk above.
- **`Require` with no profile has not been observed failing** (§6). The
  precondition, an APK with no profile at all, cannot be built here.
- Exact library and plugin versions: chosen in slice 1 and recorded in the
  report.
- The `androidx.benchmark` version, and therefore a re-check of the
  `CompilationMode.Partial` defaults against that release: done against the
  1.5.0 sources in slice 1 (§3).
- Whether the result is "no measurable difference". That is a possible
  outcome of a real measurement; if it happens it goes in the README as it is.

## 9. First measurement (slice 2)

**Not the headline and not for the README.** `CompilationMode.None()`, empty
database, cold start, release-like `benchmark` build type. This exists to show
the harness runs and reports both metrics.

- Device: Realme RMX3360 (GT Master Edition), Android 13, API 33,
  `realme/RMX3360/RE54ABL1:13/TP1A.220905.001/R.18a3a4d-41d9-71a9:user/release-keys`;
  Macrobenchmark reported `cpuLocked: true`, 8 cores.
- State at the start of the run: USB-powered (not AC), battery 100% (status
  "full"), battery temperature 30.0 C, screen awake on the launcher, no app
  in the foreground; `top` showed ~770% idle of 800%, with only system
  processes (`com.oplus.midas`, kernel workers) visible. Not checked:
  airplane mode, background apps beyond the `top` snapshot, thermal state after
  the run.
- N = 10 iterations, one run of the test (a single sample of 10, not a
  distribution across sessions).

| Metric | min | median | max |
|---|---|---|---|
| timeToInitialDisplayMs | 509.9 | 536.7 | 759.1 |
| timeToFullDisplayMs | 573.2 | 611.3 | 834.3 |

Per-iteration TTID (ms): 759.1, 535.0, 542.7, 537.7, 549.1, 551.4, 535.8,
509.9, 513.1, 516.4. Per-iteration TTFD (ms): 834.3, 611.4, 611.2, 612.9,
618.0, 622.6, 609.3, 573.2, 601.7, 581.0.

Iteration 0 is high on both metrics (759 ms against a ~535 ms TTID median,
834 ms against ~611 ms TTFD). The working explanation is that first launch
after install does one-time work the later iterations do not; it is a
hypothesis, not a finding. Iteration 0 is included in the reported
min / median / max, per §1's rule, not discarded. Caveat on "consistently": the
break (b) run below, a separate session, had its maximum (715 ms TTID) on the
last iteration, not the first, so two runs do not establish a pattern.

TTFD sits 63-89 ms after TTID per iteration (median gap 72.3 ms), so the `Loading` frame and the
first real frame are distinguishable on an empty database. This includes sync
scheduling (§3, "What else is inside the measured window").

### What the deliberate breaks showed

- **Debug build as target.** Macrobenchmark refused with `ERRORS (not
  suppressed): DEBUGGABLE`. This took two attempts. The first failed with
  "Unable to find target package dev.charanjeev.bahi" because the debug build's
  applicationId carries a `.debug` suffix, so the run never reached the
  debuggable check. The DEBUGGABLE error was observed only after correcting the
  package and installing both APKs by hand with `adb`. A check failing is not
  the same as a check failing for the stated reason.
- **Unresolved, uninvestigated (moot after slice 3: `:benchmark` no longer has a
  `debug` variant, §5 item 5):** Gradle's `connectedDebugAndroidTest` did not
  install the target app (`:app` debug), although `connectedBenchmarkAndroidTest`
  installed the benchmark variant's.
- **`ReportDrawnWhen` that never fires.** The test **passed**, and the output
  contained `timeToInitialDisplayMs` only: `timeToFullDisplayMs` was absent. It
  neither failed nor fell back to TTID's value. So a broken TTFD is silent
  unless something asserts the metric is present; `StartupBenchmark` does not
  yet, so as written TTFD's presence is checked by reading the output, not by
  the test. Slice 4 must not claim TTFD without closing that. Why it is not
  closed here: against benchmark-macro(-junit4) 1.5.0, every public
  `MacrobenchmarkRule.measureRepeated` overload returns `Unit`; `Metric` is a
  sealed class, so a wrapper metric cannot be written outside the library; and
  the one function that returns the results (`macrobenchmarkWithStartupMode`) is
  `@RestrictTo(LIBRARY_GROUP)`. The metrics reach the test's own code nowhere.
  *Slice 3 closed this from outside the test with `checkBenchmarkData`; the gap
  inside the test is permanent (§6).*
