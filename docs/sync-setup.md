# Sync setup

`docs/sync-design.md` is the design; this is the two-minute version of what a
developer building on M4b actually does. Slice by slice, per
sync-design.md §13.

## Right now (slice 9a)

Create a file named `sync.properties` at the repository root. Its contents
don't matter yet -- an empty file is enough:

```bash
touch sync.properties
```

`:app`'s build script (`app/build.gradle.kts`) checks whether this file
exists when it configures, and bakes the answer into
`BuildConfig.SYNC_CONFIGURED`. `:core:sync`'s `SyncConfiguration` interface is
how the rest of the app reads that without depending on `:app`'s generated
`BuildConfig` directly (`SyncConfiguration.kt`'s doc has the reasoning); the
Settings screen uses it to decide whether to show the "sync isn't set up"
row.

`sync.properties` is gitignored, same as `local.properties` -- it's a
per-checkout file, not something committed. Deleting it (or never creating
it) is the supported "I'm not working on sync" state: the app builds and runs
exactly as it does today, Settings says so plainly, and nothing about M4a's
merge engine or convergence suite needs it to exist.

**What this file does not do yet:** nothing reads a value out of it. There is
no OAuth client to configure, because slice 9a ships with none -- creating
the file only flips `SYNC_CONFIGURED` from false to true, which is enough to
exercise the Settings row and its test, and nothing else.
