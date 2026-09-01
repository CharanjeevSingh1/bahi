# Sync manual test plan

Linked from `docs/sync-design.md` §10.5 rather than duplicated there, since
this is a living checklist and that document is a design record. What each
row checks cannot be exercised by any automated test in this repo -- see
`docs/sync-design.md` §10.5 for why, and for how `DriveTransportContractTest`
(the one row here that *is* automated, just never by CI) is set up.

**The rule that keeps this honest:** every row carries a last-verified date
and the commit it was verified against. A row that has never been checked
says so plainly (`--`), not with a stale-looking blank. Any PR touching
`:core:sync`'s Drive-facing code re-runs every row whose "against" commit is
more than one M4b slice old and updates the date. A row whose date is more
than 90 days old (the same tombstone-horizon number the rest of this design
already uses) is a line item in the M4b release checklist -- not something CI
enforces, because CI is precisely what cannot exercise these rows.

| Check | Last verified | Against |
|---|---|---|
| `DriveTransportContractTest` passes against a real, throwaway Drive account (`./gradlew :core:sync:driveTest`, docs/sync-setup.md) | — | — |
| Two physical devices converge on a genuine field conflict | — | — |
| Token refresh survives an app process death mid-sync | — | — |
| Revoking access from the Google Account page surfaces `NeedsReauthorization` within one cycle | — | — |
| Quota exhaustion (fill `appDataFolder` deliberately) reports a terminal failure, not an infinite retry | — | — |
| Two devices compacting within the election window (§8.3, D13) -- forced by racing two emulators against one throwaway account | — | — |
| A device whose cursor predates the horizon takes the reconciliation path against real Drive data | — | — |

Empty because none of these rows has ever actually been run against a real
account by this repository's own history -- filled in as they're exercised,
the same way `docs/sync-design.md` §13's own slice list only marks a row
"done" once it is checked, not once it is planned. `DriveTransport` (slice
9e) and `DriveCompactor` (slice 9f) are both built now, so every row down
through "a device whose cursor predates the horizon..." has real code behind
it to exercise -- the "--"s are honest about verification history, not about
whether there is anything yet to verify.
