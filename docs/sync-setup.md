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

## Connecting Google Drive (slice 9d)

**Corrected once 9d actually shipped: `sync.properties` gains no new key.**
The paragraph this replaced predicted a `driveClientId` value read out of the
file; that assumed a client type where the app looks its own client ID up at
runtime. What `PlayServicesDriveAuthorization` actually calls -- Google's
Authorization API via `Identity.getAuthorizationClient` -- resolves the OAuth
client automatically from the calling app's package name and signing
certificate, the same way `GoogleSignInClient` always has. So the setup is a
Google Cloud Console step, not a code or config one: register an **Android**
OAuth client (not Web, not Desktop) against this app's package name and the
debug keystore's SHA-1 --

```bash
keytool -list -v -keystore ~/.android/debug.keystore
```

(password `android` unless changed) -- request only the `drive.appdata`
scope, and nothing else has to change. `sync.properties` still exists purely
to flip `SYNC_CONFIGURED` (slice 9a, above); it has never needed a value in
it and still doesn't.

Once that client is registered, tapping "Connect" on the Settings screen's
Google Drive row (docs/sync-design.md §8.6, slice 9d) walks through Google's
real consent screen. Nothing in the app calls `SyncEngine.sync` yet, so
connecting doesn't make anything sync -- that's slice 9g.
