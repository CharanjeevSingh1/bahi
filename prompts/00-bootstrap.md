# Task 00 — Get the M0 scaffold building

Paste this into Claude Code from the repo root, after `git init` and an initial commit.

---

This repo is an M0 scaffold for a multi-module Android app. The structure and architecture are intentional and already documented in `CLAUDE.md` and `README.md` — read both first.

The scaffold has **never been compiled**. Your job is to make it build and pass its tests **without changing the architecture**.

## Do this in order

1. Run `gradle wrapper --gradle-version 8.14.2` to generate the wrapper JAR and scripts. Commit them.

2. Resolve the dependency versions in `gradle/libs.versions.toml`. They were written from memory and are unverified. Check that AGP, Kotlin, KSP, Compose BOM, Hilt and Room versions are mutually compatible and available. KSP must exactly match the Kotlin version. Update the catalog — do not inline versions in module build files.

3. Run `./gradlew assembleDebug` and fix compilation errors. Expect problems in `build-logic/` first, since convention plugins compile before everything else.

4. Run `./gradlew testDebugUnitTest` and make the existing tests pass. If a test fails because the *test* is wrong, fix the test. If it fails because the *code* is wrong, fix the code and say which you did.

5. Run `./gradlew :core:database:kspDebugKotlin` to export the Room schema, then commit `core/database/schemas/`.

6. Run `./gradlew checkModuleBoundaries` and `./gradlew moduleGraph`. Replace the hand-written Mermaid block in `README.md` with the generated output from `build/reports/module-graph.md`.

7. Run `./gradlew lintDebug` and fix real issues. Suppress nothing without explaining why.

## Constraints

- **Do not change the module structure.** If a module seems unnecessary, say so and stop; don't delete it.
- **Do not add dependencies** beyond what's needed to make existing code compile. If you think one is needed, ask first.
- **Do not weaken anything to make it pass.** Specifically: no `fallbackToDestructiveMigration()`, no deleting the failing test, no `@Suppress` to silence a real problem.
- If something in the scaffold is genuinely wrong (a bad API, an impossible configuration), fix it and tell me what was wrong and why.

## When done

Report: which versions you changed and why, which files you had to fix, anything in the scaffold you think is a design mistake, and the output of the four Gradle commands above.
