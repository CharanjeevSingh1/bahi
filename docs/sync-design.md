# Sync — design

M4. Covers what sync is actually for here, what a backend costs a finance app,
the revision model the M0 columns don't have, per-field conflict resolution and
why most of the M0 sketch is wrong, the interactions with M2/M3, tombstone
lifetime, and how two-device convergence gets tested on a CI emulator with no
credentials. No code here is meant to be copy-pasted; it's there to make the
reasoning checkable, the same way `docs/csv-import-design.md` and
`docs/budgets-design.md` are.

Related reading: `core/sync/.../ConflictResolver.kt` (the M0 placeholder),
`core/model/.../SyncState.kt` (`SyncMetadata`, `PendingOperation`, `SyncStatus`),
`core/database/.../TransactionDao.kt` (`pendingChanges`, `markSynced`,
`softDelete`, `undoSoftDelete`, `softDeleteBatch`, `applyRuleCategory`,
`importBatch`), `core/database/.../CategoryDao.kt` (the one table with no
tombstone), `core/data/.../OfflineFirstBudgetRepository.kt` (`upsert`'s
natural-key invariant, and the `combine` transient), `docs/budgets-design.md`
§2.2 and §1.4.

The headline: **the interesting half of M4 needs no network at all**, and this
document recommends building that half first and on its own. §14 is the
decisions list; D3 is the one that decides the shape of everything else.

---

## 1. What sync is for here, and what it is not

**One human, two or more devices they own.** A phone and a tablet; a phone and
its replacement. Not sharing a ledger with a partner, not a household, not
multi-user anything. That single sentence does more scoping work than it looks
like:

- There is no permissions model, no invitations, no per-row ownership, and no
  question of who is allowed to see what. Every device holds the whole ledger.
- There is exactly one writer identity, so a "conflict" is never a
  disagreement between two people. It is one person who edited the same thing
  in two places, usually because they forgot they'd already done it. That
  matters for resolution policy: the right answer is almost always "don't lose
  either version and tell them," not "adjudicate."
- Device count is small (2–3) and long-lived. Anything whose cost scales with
  device count is fine.

**Not in scope, stated so nobody has to guess:** real-time propagation (sync is
periodic and on-demand, not a live socket), server-side query or aggregation
(reads still come from Room, always — the README's "network as an optimisation
rather than a prerequisite" is the whole architecture), and web or desktop
clients.

### 1.1 What syncs, table by table

| Table | Syncs? | Why, and what makes it hard |
|---|---|---|
| `transactions` | Yes | The volume, and the only table where rows are partly machine-generated. Every hard case in this document is a transaction case. |
| `budgets` | Yes | Tiny and user-authored, but has a natural key (`category_id`, `year_month`) that the DB does not enforce — see §5.2. |
| `category_rules` | Yes | Tiny and user-authored. `priority` is a position in an ordered list, which is the classic sync-hostile data type — see §5.3. |
| `categories` | **Yes, and it can't be, today** | See §1.2. This is the finding that most changes the shape of M4. |
| import batches | No such table | `import_batch_id` is a column on `transactions` and rides along with the rows. §6.1 covers whether that's enough. |
| `sync_shadow`, `sync_conflicts` (new, §4) | No | Local bookkeeping about sync. Syncing it would be circular. |
| DataStore preferences (`lastSyncCursor`) | No | Per-device state by definition. |

### 1.2 Categories cannot be excluded, and cannot be included as they stand

The tempting scope cut is "sync transactions, budgets and rules; leave
categories alone, they're mostly seeded anyway." It doesn't survive contact with
the schema.

`transactions.category_id` is a foreign key into `categories`
(`ON DELETE SET_NULL`). A transaction synced from device A carrying
`category_id = "cat-user-coffee"` cannot be inserted on device B unless that
category row exists on B. So excluding categories leaves exactly two options at
apply time: fail the insert, or null the category out. Nulling it out is
*precisely* the failure the README names as the reason this milestone exists —
"last-write-wins on a whole row silently discards a category the user set on
their phone" — arrived at by a different route. Categories are load-bearing for
transaction sync whether or not anyone wanted to sync them.

But `categories` is also the one table in the schema with **no tombstone and no
sync bookkeeping at all**:

```kotlin
@Query("DELETE FROM categories WHERE id = :id AND is_system_defined = 0")
suspend fun deleteUserCategory(id: String)
```

That is a hard delete, in a codebase whose rule 7 says soft deletes only. It
predates the rule being load-bearing and nothing has caught it because nothing
has needed it yet. Two consequences under sync, both bad:

- **Deletes don't propagate, they reverse.** Device A hard-deletes "Coffee".
  There is no tombstone, so there is nothing to push. Device B still has the
  row, pushes it as an ordinary unchanged record, and A re-creates it on the
  next pull. The category comes back, and it comes back *silently*.
- **The cascade fires on the wrong device.** `budgets` and `category_rules`
  both declare `ON DELETE CASCADE` on `category_id` (budgets-design §4.1). A
  hard delete on A takes A's budgets and rules for that category with it,
  invisibly, with no tombstone for any of them either — so B repopulates all of
  them too.

So M4's first migration is not a sync feature at all: **`categories` gets
`deleted_at`, `local_revision`, `remote_revision` and `pending_operation`, and
`deleteUserCategory` becomes a soft delete.** Purely additive columns, so it is
an `ALTER TABLE ADD COLUMN` migration, not a table rebuild (§6.4 has a
consequence of that).

Two details that fall out and are easy to get wrong:

- **Every existing category query needs `deleted_at IS NULL`.** `observeAll`,
  `getById`, and the seeding path. `insertAllIgnoringConflicts` for system
  categories deserves a second look: a system category the user somehow
  tombstoned would be silently un-ignorable by a fresh seed (the row still
  exists, so the conflict is still ignored, so the tombstone stands). System
  categories should be exempt from soft delete the same way they are exempt
  from hard delete today — the existing `is_system_defined = 0` guard moves
  onto the `UPDATE`, unchanged in meaning.
- **A soft-deleted category no longer nulls its transactions' `category_id`.**
  `ON DELETE SET_NULL` only fires on a real delete. That is the better
  behaviour and should be adopted deliberately rather than tolerated: the
  categorisation is retained, so undeleting the category restores it, and no
  fan-out write is needed (which would otherwise turn one delete into hundreds
  of transaction updates, each of them its own sync operation). The cost is
  that "category_id points at a tombstoned category" has to read as
  uncategorised.

**Two corrections from building slice 1**, both to that last point.

*"In one place — the mapping layer" was wrong.* Display takes care of itself:
every screen resolves a category by looking its id up in the list from
`observeCategories()`, which now filters tombstones, so the lookup misses and
the row already renders as uncategorised. What does **not** take care of itself
is a query. `TransactionDao.observeUncategorisedSpend` matches `category_id IS
NULL`, and an orphaned transaction's `category_id` is not null — while its
budget *was* tombstoned by the cascade below. So that spend would have been in
no budget and not in the uncategorised line either: money on the ledger and on
no line of the budgets screen. That query now also excludes ids with no live
category, which has the side benefit of putting `categories` in its
invalidation set, so the uncategorised total updates the moment a category is
deleted rather than on the next unrelated write. The general rule this is an
instance of — that the remainder of a partition must be written against the
absence of a *match*, not the absence of a *value* — is recorded in
budgets-design §2.2, next to the queries it governs, rather than here: the
next person to divide that total is reading that file, not this one.

*The cascade has to be driven by hand, and it is not the repository's job.*
`budgets` and `category_rules` declare `ON DELETE CASCADE` on `category_id`,
and a soft delete fires no foreign key — so without something replacing it, a
deleted category leaves live budgets and rules behind, and under sync leaves
them with no tombstones of their own for the other device to apply. Replacing
it in the repository would mean three DAO calls in three separate transactions,
and the property that made the foreign key trustworthy is that it could not
half-happen. So it is one `@Transaction` method on `CategoryDao` that writes
all three tables, which departs from the rule
`TransactionDao.observeUncategorisedSpend`'s own comment states — a DAO
queries the table it owns, and composing across tables is the repository's job.
The exception is narrow, and the KDoc says why: this is not composition, it is
a database constraint being re-implemented in application code, and atomicity
is the whole of what it is for.

---

## 2. Scope recommendation: build the engine first, and separately

The README names sync conflict resolution as one of two genuinely hard problems
in this repo. It is worth being precise about *which part* of sync is the hard
problem, because the answer determines what is worth building first.

The hard part is **convergence under concurrent divergent edits**: two devices
that have both been offline, both changed overlapping state, and now have to end
up agreeing — without either user's intent being silently thrown away. That
problem is entirely on-device. It needs a revision model, a merge function, and
a way to order operations. It does not need a network, a server, an account
system, or a single line of HTTP.

The other part — auth, tokens, a place to put bytes, quota, retries, key
management, a privacy story — is real work and mostly *operations* work. It is
where the schedule risk lives and where almost none of the design interest does.
It is also the part that, done badly, is invisible; whereas the merge engine done
badly is the thing the README's claim is about.

**Recommendation: split M4.**

- **M4a — the engine.** Everything in §3–§7 of this document: the identity and
  revision model, `ConflictResolver`, the pull/merge/apply/push loop, the
  conflict record, the two-device convergence suite. The transport is an
  interface with one implementation: an in-memory fake. Nothing ships to a user
  and nothing leaves the device.
- **M4b — the transport.** §8: a real backend behind that interface, plus auth,
  encryption, and the settings UI to turn it on.

M4a is a complete, reviewable, fully-tested milestone whose artifact is exactly
the thing a reader of this repo came to see. M4b makes it usable. **If only one
gets built, M4a is the one** — and the README's claim becomes true at the end of
M4a, not M4b.

The obvious objection is that M4a ships nothing a user can touch, which in a
portfolio repo reads as an unfinished milestone. The mitigation is cheap and
worth doing inside M4a: the convergence suite is the deliverable, so it gets
named in the README's testing table alongside the migration tests, and the
Settings screen gains a "Sync — not configured" row rather than nothing. See D3.

**Decided: M4a only.** The plan of record is slices 1–8. M4b is deferred rather
than scheduled — the README roadmap lists it after M5, with the reason — so
slice 9 below is a sketch of what the transport will have to do, not a queued
piece of work. Everything M4a builds is transport-agnostic by construction, so
deferring it costs nothing but the ability to actually sync two phones.

---

## 3. Identity: the problem that comes before conflict resolution

Conflict resolution assumes two devices are looking at *the same row*. Both
places this app creates rows, that assumption is currently false.

### 3.1 Two devices importing the same statement

The likeliest thing a two-device user does is import January's statement on the
phone, then import it again on the tablet — the same file, or an overlapping
re-export. Locally, `TransactionDao.importBatch` handles that perfectly:
`content_hash` plus the count-aware quota from csv-import-design §4 recognises
the second import as duplicates.

Across devices it does nothing at all. De-duplication runs at import time
against the local table. Device A's 400 rows and device B's 400 rows are 800
rows with 800 different UUIDs, all created before either device syncs. After
sync both devices hold all 800, every transaction is doubled, every budget total
is doubled, and nothing in the app ever runs de-duplication again.

**So the content hash neither helps nor hurts sync as it stands — it is simply
not consulted.** That's the honest answer to whether it helps. The interesting
question is whether it *could* be made to help, and there the answer is yes, in
a way that makes one mechanism serve both problems.

Three options (D4):

**(a) Accept the duplicates, surface them, give the user a tool.** Convergent
and honest — both devices end up with the identical 800 rows — but 800 rows is
not something a user cleans up by hand, so "a tool" means a whole
duplicate-detection-and-merge feature that does not exist and would be its own
milestone.

**(b) Derive the row id from the content, for imported rows only.** A
`CSV_IMPORT` transaction's id becomes `h1:<hash>#<n>`, where `<hash>` is over
the same tuple `contentHashOf` already uses (account, date, amount minor units,
upper-trimmed description) and `<n>` is the occurrence index *within that
tuple*, assigned by the same count-aware quota `importBatch` already computes.
Both devices importing the same statement generate byte-identical ids, so the
duplicate is not de-duplicated — it never exists. Two genuinely identical
coffees get `#0` and `#1` on both devices and both survive, which is the case
csv-import-design §4 was fixed for. A device that imported two of a tuple
merging with one that imported three converges on three, correctly, with no
special logic.

Manual entries keep UUIDs. Two devices are not going to independently type the
same transaction and mean one; if they both typed it, there are two.

**(c) An alias table** mapping "local row X is the same thing as remote row Y",
populated at apply time by a hash lookup. Correct and fully general, and the
most machinery of the three: aliases themselves have to sync, be transitive, and
survive edits to the fields the hash is over.

**Recommendation: (b).** It converts an ugly, likely failure into a case that
cannot arise, and it does it by reusing the quota logic that already exists
rather than adding a mechanism.

Two things it needs, and one risk that has to be said out loud:

- **`contentHashOf` has to stop using `String.hashCode()`.** It currently is:

  ```kotlin
  listOf(...).joinToString(separator = "|").hashCode().toString()
  ```

  `String.hashCode` is specified by the JLS, so it is stable across devices and
  JVM versions — the property that matters most, and the reason this is not
  already broken. But it is 32 bits. As a local de-duplication key that is
  survivable; a collision merges two unrelated transactions in one import, which
  is bad but bounded. As a **primary key** it is not survivable: at a few tens of
  thousands of rows the birthday bound makes a collision likely, and a collision
  now means two different transactions are the same row forever, on every device.
  Option (b) requires SHA-256 truncated to 128 bits, via
  `java.security.MessageDigest` — platform API, no dependency.
- **The `h1:` prefix is doing real work.** It is a version tag on the id scheme.
  If the tuple or the hash function ever has to change, new rows get `h2:` and
  old rows keep `h1:` — the two coexist, and the only degradation is that a
  re-import across the version boundary stops de-duplicating and falls back to
  case (a). Without the prefix, changing the scheme means rewriting primary keys
  on every device and every row on the remote, which is a door that does not
  open again.
- **The risk that remains:** the id is now derived from a description string
  that different exports of the same transaction can render differently
  (trailing whitespace is normalised, an extra reference number is not). Where
  that happens, `h1:` ids differ, and this degrades to case (a) — duplicates —
  rather than to anything worse. That is the same fragility `content_hash`
  already has; option (b) does not add it, it makes it visible in a place where
  it can't be quietly ignored.

`content_hash` stays as a column even though the id now contains it: the local
de-duplication query is an indexed exact-match on that column, and rewriting it
as a `LIKE 'h1:<hash>#%'` prefix scan to save one column would be a worse query
for no benefit. The redundancy is deliberate and worth one comment at the
column.

#### The rows that are already there

Every transaction in the database today has a UUID id, imported ones included.
Three ways to go, and this is a one-way door, so the argument is here rather
than in a commit message.

**Leaving them is worse than it first looks.** The tempting reading is that
only a straggler device is affected, and that stragglers are rare. That is not
the scenario. Both devices migrate; what is old is the *data*, not the build.
A user who has been running Bahi on a phone and a tablet offline for months and
then turns sync on has their entire history on both sides, under two disjoint
sets of UUIDs. Leaving pre-existing rows alone means the very first sync —
the one this whole milestone exists for, and the one carrying the most rows it
will ever carry — doubles everything, and §3.1 has already said that 800
duplicate rows is not something a user fixes by hand. The divergence would not
be a rare corner. It would be the common case, once.

**Rewriting them is contained, and I checked rather than assumed.** No foreign
key targets `transactions` or `budgets`; `categories` is the only parent table
in the schema. `UserPreferencesDataSource` holds one key, `last_sync_cursor`,
and no row ids. The only things outside the table that hold a transaction or
budget id are navigation arguments in `SavedStateHandle`, which are
process-local, do not survive the app update that triggers the migration, and
whose worst case is a detail screen that reports the row as missing. Nothing
has to be carried along with the rewrite, which is the thing that usually makes
a primary-key migration go quietly wrong.

**And the decisive argument is that the migration is happening anyway.**
Moving `contentHashOf` off `String.hashCode()` changes `content_hash` for
*every* row, imported or manual, synced or not. If the migration does not
recompute that column, the next import computes SHA-256 hashes that match
nothing stored, `countExistingHashes` returns zero for every hash, and the user
re-importing an overlapping statement gets their whole overlap inserted a
second time. That is a local, single-device, present-day data bug with no sync
anywhere near it. So a Kotlin-side pass over every transaction row is required
regardless of what is decided about ids. Rewriting the id of the `CSV_IMPORT`
subset while that loop is already open is close to free, and choosing *not* to
would mean deliberately writing a migration that fixes one field and declines
to fix the other while holding both in its hand.

**Decision: rewrite.** `CSV_IMPORT` rows get `h1:` ids derived from their own
stored columns; `MANUAL` rows keep their UUIDs, per the rule above. Budget ids
become their natural key (§3.2). What this does *not* fix is stated in §3.1
already and does not change: two devices whose bank exports render the same
transaction differently still produce different hashes and still duplicate.
Rewriting makes identical histories converge; it cannot make different renderings
identical.

### 3.2 Two devices creating the same budget

`budgets` has a real natural key — one budget per category per month — that the
database deliberately does not enforce (budgets-design §4.1: a partial unique
index over `deleted_at IS NULL` is what's needed, and Room's
`@Entity(indices = ...)` cannot declare one). The invariant lives in
`OfflineFirstBudgetRepository.upsert`, which looks up the active row for
`(categoryId, yearMonth)` and reuses its id.

**Sync does not go through that repository.** Device A and device B both create
an August Food budget while offline. Two rows, two UUIDs, same natural key. Both
sync. Both devices now show two "Food, August" rows with different limits, and
the repository's invariant — which is still perfectly correct about every write
*it* handles — cannot fix it, because the second row didn't come through it.

The fix is the same trick as §3.1 and is cheaper here because there is no hash
involved: **the budget's id is the natural key.**
`budget:<categoryId>:<yearMonth>`. Both devices generate the same id, so what
was an unfixable duplicate becomes an ordinary same-row conflict on
`limit_minor`, which the resolver handles like any other field. The repository's
lookup-then-reuse logic stays (it is still the thing that keeps `id` from being
what identifies the row on the way in) but it stops being the *only* thing
standing between the user and two Food budgets.

Rewriting existing budget ids is a data migration, but a contained one: nothing
has a foreign key pointing at `budgets`, and `Budget.id` is not shown to the
user or stored anywhere outside the table. Same for the transaction ids in
§3.1 — no foreign key targets `transactions` either.

**One thing that paragraph got wrong: the rewrite cannot be a blanket
`UPDATE`.** `budget:<categoryId>:<yearMonth>` is unique per key, but the rows
holding that key today are not. `BudgetDao.findActive` filters
`deleted_at IS NULL` precisely so that deleting an August Food budget and
creating another one leaves a tombstone and a live row sharing the key — that
is not a corruption, it is the documented behaviour (budgets-design §4.1). A
blanket update maps both onto the same primary key and the migration fails on a
constraint violation, on the devices of exactly the users who have used the
feature most.

So the rewrite picks one claimant per key: the live row if there is one
(newest `updated_at`, ties broken by id so it is deterministic across devices),
otherwise the newest tombstone. Everything else keeps its UUID. The
consequence, stated rather than discovered later: a tombstone that did not win
the key is a delete that will not propagate — the other device's live row has
the natural-key id and never sees the tombstone's. Re-deleting costs one tap,
which is the same trade §5.3 makes deliberately, and it is bounded to budgets
deleted before this migration ran.

A live row losing to another live row cannot happen through the repository, and
if it somehow has, the migration leaves the loser alone rather than deleting it.
A migration that silently discards a row the user can see is a worse failure
than two rows the user can see.

**One behaviour change falls out of this and is worth naming.** Deleting an
August Food budget and creating another one used to leave a tombstone and
insert a second row. Now both are the same id, so the second `upsert` revives
the tombstoned row. Visually identical; better under sync, because the user's
last word on (food, August) is one budget rather than a delete and an unrelated
insert that another device has to order correctly. It also makes the
resurrection case in §4.3 reachable in production for the first time —
`findActive` filters tombstones, so the revived row's `local_revision` resets
to 1. Inert until slice 5 reads those columns, and slice 3 fixes it before
anything does.

`category_rules` gets no equivalent treatment, deliberately: it has no natural
key, and budgets-design §4.1 already records why giving it one would be a
mistake (two rules may legitimately share a merchant string pointing at
different categories, and budgets-design §1.5 resolves that by priority
rather than forbidding it).

---

## 4. The revision model — the part the M0 columns don't have

`TransactionEntity` carries `local_revision`, `remote_revision`,
`pending_operation` and `deleted_at`, and `SyncMetadata` mirrors them. That is
enough to answer "has this row changed since I last synced it" (`local_revision`
moved, `pending_operation` is set). It is **not** enough to answer the question
conflict resolution actually asks, which is: *of the fields that differ between
my copy and yours, which ones did I change and which ones did you?*

The M0 `ConflictResolver` interface knows this — `resolve(local, remote, base)`
has a `base` parameter, which is exactly right. What's missing is that **nothing
in the schema stores the base.** `remote_revision` is a number; a three-way
field merge needs the base row's *values*.

### 4.1 Options, and why the shadow table wins

**(i) No base — two-way merge.** Compare local and remote field by field and
apply a policy per field. This is what the M0 comment sketches ("amount, date,
description → remote wins"), and it is the reason that sketch is wrong (§5.1):
without a base you cannot tell "I changed the amount and you didn't" from "you
changed it and I didn't." Every differing field looks like a conflict, so every
field falls to its policy, so a device that changed nothing at all can still
overwrite a field the other device deliberately edited.

**(ii) Per-field dirty flags.** A bitmask column recording which fields this
device has changed since its last sync. Cheap in storage, exact for the common
case. The cost is that **every write path has to set the right bits**, including
ones that don't currently know what changed — `TransactionDao.update` is a
whole-row `UPDATE` that overwrites twelve columns whether or not they differ, so
the repository would have to read the old row and diff it first. Adding a
requirement that every present and future write path must remember something is
the opposite of what this codebase does with invariants (budgets-design §1.4:
the guard goes in the one write path's `WHERE` clause precisely so callers
cannot forget).

**(iii) A shadow table** holding the exact last-synced state of every synced
row:

```
sync_shadow
  table_name       TEXT NOT NULL     -- 'transactions' | 'categories' | 'budgets' | 'category_rules'
  row_id           TEXT NOT NULL
  remote_revision  INTEGER NOT NULL
  payload          TEXT NOT NULL     -- the row as it was at that revision, JSON
  PRIMARY KEY(table_name, row_id)
```

Written by exactly one caller — the sync engine, at apply and at
push-acknowledged — and read by exactly one caller, the resolver. **No existing
write path changes at all.** `local_revision` already tells the engine which
rows are dirty; the shadow tells it what they looked like before. That property
is worth more here than the storage it costs, which at this scale is a second
copy of a few thousand small rows.

**Recommendation: (iii).** See D5.

Two honest costs:

- **If the shadow is lost, everything looks concurrently modified.** A device
  restored from an Android backup, or a bug that clears the table, degrades
  every subsequent merge to case (i). The mitigation is to make that state
  explicit rather than silent: a missing shadow entry for a row that exists on
  both sides is treated as a genuine conflict and *recorded as one* (§5.6), so
  the user sees a burst of conflict records rather than a burst of silent
  overwrites. A shadow that is missing entirely (fresh install, restore) takes
  the full-reconciliation path in §7, which rebuilds it.
- **The shadow is whole-row, so it cannot distinguish "changed and changed
  back" from "never changed."** That is the correct answer anyway — a field
  whose value equals the base did not change, whatever route it took — so this
  is a limitation only in the sense that it can't attribute intent. Nothing in
  this design needs it to.

#### When a shadow row is written

Exactly two moments, and both of them mean the same thing: *this device and the
remote now agree about this row.*

1. **After a remote op is applied.** The state that was written locally becomes
   the base, at that op's `remote_revision`.
2. **After a push is acknowledged.** The state that was pushed becomes the base,
   at the revision the remote assigned it.

Both happen inside the engine's per-batch `@Transaction`, alongside the write to
the data row itself. The shadow and the row's `remote_revision` move together or
neither moves {d} which is what lets a shadow be *stale* (a device that has not
synced in a month holds a month-old base, correctly) without ever being *wrong*.

Nothing else writes it. No user write path, no repository, no migration. That is
the property option (iii) was bought with, and it is the kind that erodes without
anything failing: the day some other code writes a shadow to keep it fresh, the
base stops meaning "what the remote last saw" and every merge that consults it is
quietly answering a different question.

**Corrected while building slice 6: moment 2 did not exist.** `SyncEngine.push`
called `markSynced` on each repository -- which clears `pending_operation` and
sets the row's own `remote_revision` column -- and nothing else. No call
anywhere wrote `sync_shadow` on push acknowledgement; the only writer was
`RoomSyncApplier`'s apply path, i.e. moment 1 alone. Since `dirtyRows` judges a
row dirty by comparing `local_revision` against `sync_shadow.remote_revision`
(§4.3, §5a) -- not against the `remote_revision` *column* `markSynced`
updates -- a device that had only ever pushed, never applied, kept `dirtyRows`
returning that row forever: `local_revision` had advanced, the shadow never
had. The two-device harness's simplest possible scenario (one edit, one
device, sync to quiescence) hit this immediately -- quiescence was never
reached. Fixed by adding `SyncApplier.recordPushed(table, rowId,
remoteRevision, payload)`, called from `SyncEngine.push` for every row whose
`markSynced` guard actually succeeded (a guard failure means the row moved
under the push and has not settled at this revision, so recording it as agreed
would be recording a lie -- the same reasoning `markSynced`'s own guard uses).
One second-order effect worth stating rather than leaving implicit: writing
"what I just pushed" as my own shadow immediately, before the peer has pulled
it, means a device treats its own pending edit as settled the moment it is
sent. In a genuine two-sided conflict on one field, this makes the resolver's
policy get evaluated on only *one* of the two devices in practice -- whichever
device has *not yet* pushed its own conflicting edit when the peer's arrives
computes the real merge; the other device, having already moved its own
shadow forward, sees its own value as "unchanged since base" and simply
fast-forwards to whatever the first device resolves to. Convergence still
holds (both devices end up applying the same one decision), but it means a
policy that is not commutative between the two sides -- `REMOTE_WINS` on a
field, tried deliberately while verifying this suite -- does not reproduce as
a convergence failure the way asymmetric-policy intuition suggests, because
the "other side" of the comparison never actually gets evaluated twice.

Room cannot enforce that a shadow's `remote_revision` matches its row's, because
`sync_shadow` names its parent table in a *column* and SQLite has no polymorphic
foreign key. So the revision is an explicit parameter of every write rather than
something inferred, and the DAO offers no way to write a shadow without stating
one.

#### Named assumption: both devices run identical field policies

The paragraph above is a correctness finding, not just an implementation
curiosity, and it is worth stating as its own assumption because everything
downstream of it — the field-coverage test, the convergence suite, the D3
decision to ship the engine without a transport — is built on top of it without
saying so.

**The assumption.** `resolveField` and `fieldPoliciesFor` (`FieldPolicies.kt`)
are pure functions of `SyncTable` and field name, compiled into the app. There
is no policy identifier on the wire — `SyncOp` and `OpBatch` carry
`OP_FORMAT_VERSION` for the *shape* of an op, but nothing names which
*policy table* produced or should consume one. The design has always assumed,
implicitly, that the two devices doing a merge agree on what a policy for a
given field is, because in every build so far there has only ever been one
policy table to agree with.

**Why the paragraph above matters here.** Because push acknowledgement writes a
device's own shadow before the peer has pulled, a genuine two-sided conflict on
one field is, in practice, evaluated by whichever device has *not yet* pushed
its own conflicting edit when the peer's arrives — the other side never
consults its own policy for that field at all, it just fast-forwards to the
first device's answer. That means **resolver symmetry is untested by
construction**: nothing in this codebase's convergence suite (§10) can catch
two devices disagreeing about a field's policy, because the suite runs one
build against itself and every merge in it is, structurally, only ever decided
by one side.

**The failure mode this creates: version skew.** Device A is on an older build
whose policy table says `REMOTE_WINS` for some field; device B has upgraded to
a newer build that changed that field to `USER_PROMPT`. Both fields are still
named `amount_minor` in the payload — `OpBatch.isReadable` has nothing to say
here, because the op's *shape* did not change, only what a receiving device
does with a tie on one of its fields. Whichever device evaluates the merge
(by the paragraph above, the one that has not yet pushed its own conflicting
edit) applies *its own* policy, unaware the other side would have decided
differently. The two devices still converge on whatever that one side decided
— convergence only requires that both sides end up applying the same op, not
that both sides would have chosen it — but "converges" and "resolves the way
the user of either device would expect" are not the same claim, and this is a
case where they can quietly separate: the newer device's user configured (or
inherited) `USER_PROMPT` expecting to see a conflict recorded in `sync_conflicts`
and reversible per §5.6, and instead gets a silent `REMOTE_WINS` because the
older device happened to be the one holding the unpushed edit when the merge
ran.

**Does anything detect it? No, and here is why that is accepted for now.**
There is no policy version and no policy hash carried on `SyncOp` or
`OpBatch`, so a device has no way to know its peer's policy table differs from
its own, let alone refuse or flag a merge because of it. This is accepted
rather than fixed for the same reason `OP_FORMAT_VERSION` exists as a single
top-level integer rather than one per table or per field: this is a
single-developer portfolio project with one build in the field at a time — the
"two app versions syncing" scenario is real in principle (an app update that
does not land on both devices simultaneously) but does not yet have a shipped
case to design against, and a per-field policy version would be machinery
built for a skew scenario nobody has hit. What makes this different from an
ordinary "not built yet" gap is that it is silent: `OpBatch.isReadable`
degrades visibly (a batch from the future is skipped, not misapplied), but a
policy disagreement on a field both devices already know about produces no
skipped batch and no log line — the merge simply runs to completion, on
whichever side evaluates it, using that side's table. If this project ever
carries two policy tables at once — a staged field-policy migration, or a
public release where update adoption cannot be assumed synchronous — the fix
belongs in the same family as `OP_FORMAT_VERSION`: a policy table version
(or, more precisely, a hash of the effective policy map for the table in
question) carried on the op, checked by the receiving device, and treated the
same way an unreadable future batch is — skipped and surfaced, not guessed at.
Until then, this is a documented assumption, not a solved problem: it holds
because there is exactly one policy table in existence, and it stops holding
the moment there are two.

#### The first sync, when there is no shadow for anything

Not an edge case. On the first sync every row on both devices has no base, so
whatever this design does here is what it does to the user's entire history,
once. **A missing shadow is information, and the failure mode is discarding it.**

The tempting shortcut is to synthesise a base from one of the two sides. Neither
side works. Using the local row asserts "I have changed nothing since we last
agreed", which hands every differing field to the remote; using the remote row
asserts the mirror image. Either one converts an honest *I don't know which of us
changed this* into a confident answer, silently, with nothing recorded, on every
row of the first sync. That is option (i) with extra steps.

What happens instead, by case {d} decided by `remote_revision`, not by the shadow:

**(a) `remote_revision IS NULL`: the remote has never seen this row.** Not a
conflict, a creation. Push it; the shadow is written when it is acknowledged.
There is nothing to merge against because there is nothing to merge. This is
almost the whole of the first sync on whichever device goes first.

**(b) Both sides have the row, no base, payloads equal.** Also not a conflict:
there is nothing to resolve. Adopt the remote revision, write the shadow, done.
This is the majority case on the *second* device, and slice 2 is what makes it
so {d} two devices that imported the same statement derive the same `h1:` id from
the same bytes, so those rows meet, and they meet identical.

**(c) Both sides have the row, no base, something differs.** The only genuinely
ambiguous case, and it has no correct answer: "who changed this" is not
answerable without a base. It goes to the field policies, and every field that
had to consult one is recorded in `sync_conflicts` (§5.6) {d} a decision made
without evidence is exactly the kind that has to stay reversible.

Case (b) is what keeps case (c) from being every row, and it holds for a specific
and fragile reason: **the payload excludes per-device metadata.** `created_at` is
the one that matters. Two devices importing the same statement a week apart
produce byte-identical rows whose `created_at` differ by a week; put that column
in the payload and every shared row lands in case (c), so the first sync of a
two-year history writes a conflict record per row {d} the list-nobody-reads
failure §5.6 exists to avoid, manufactured by the design that warned about it.
It is excluded, along with `updated_at` (already a field of the op) and
`content_hash` (derived from four columns the payload already carries).
`created_at` is instead resolved at apply as `min(local, remote)`: the row was
created when the first device created it, which converges whatever order the two
sync in and has no losing value to record. The full list is in `SyncPayloads.kt`,
each exclusion with its reason.

**Corrected while building slice 5c: `min(local, remote)` is not literally
computable, and the actual rule is weaker.** `remote`'s `created_at` is never on
the wire at all — it is excluded from the payload for the reason two paragraphs
up, and `SyncOp` has no separate field for it the way it does for `updated_at`.
So an applying device cannot compare the two values; it can only decide what to
do with the one it has. The rule `SyncApplier` actually implements is the
literal reading of "when this device first held the row" a few lines up: a row
this device already has keeps its own `created_at` untouched, whatever the
remote side's happens to be; a row arriving for the first time (no local row to
keep a value from) gets apply time. That converges the common case — two
devices that meet with the *same* content-derived row already independently
imported keep their own, unrelated `created_at`s, which is fine, since nothing
compares it — and is honestly weaker than "the row was created when the first
device created it" for a row this device is only now creating on this pull:
it gets *this* device's apply time, not the true first-creation instant, and
nothing corrects that later. Harmless, because `created_at` carries no
information anything else reads (§9's module-placement note makes the same
point about `SyncPayloads.kt` being one-directional for exactly this reason),
but the stronger claim above should not be read as implemented.

Two bounds on how bad case (c) can get. Both are consequences of slice 2 rather
than assumptions:

- **Independent creation of the same id only happens for content-derived ids,
  and for exactly those the identity fields cannot be what differs.** An `h1:`
  id *is* the hash of (account, date, amount, description), so all four are equal
  by construction; only `category_id`, `merchant`, `notes` and
  `category_locked_by_user` are left to disagree about. A `budget:` id fixes the
  category and the month, leaving `limit_minor` and `currency_code`.
- **A UUID-keyed row cannot reach case (c) by independent creation at all**, since
  two devices cannot arrive at the same UUID. For manual transactions, categories
  and rules, case (c) means the shadow was lost {d} the restore scenario above,
  not a first sync.

### 4.2 What an operation is

Sync exchanges **row-level operations**, not table snapshots:

```
SyncOp(
    table: String,
    rowId: String,
    remoteRevision: Long,      // assigned by whoever accepted the op; monotonic per table
    deviceId: String,          // the device that authored it
    updatedAt: Long,           // the row's own updated_at, NOT a sync timestamp -- see §5.5
    payload: Map<String, JsonElement>?,   // null = tombstone
)
```

Batched into `OpBatch(deviceId, seq, ops)`. The payload is a field map rather
than a serialised entity on purpose: entities never leave `:core:data`
(rule 3), and a field map is what lets the resolver work per field and what lets
an unknown field from a newer app version be carried through rather than
dropped. Serialisation is `kotlinx-serialization-json`, already in the catalog
and already a dependency of `:core:sync`. `Money` and `YearMonth` are value
classes over `Long` and `String`; they serialise as those and must be pinned
that way explicitly rather than left to inline-class defaults.

### 4.3 The three existing sync hooks, and the bug in one of them

`TransactionDao` already has `pendingChanges(limit)` and `markSynced(id,
remoteRevision)`. Neither is called from production code today — only from
`FakeTransactionDao` — so neither has ever run. `markSynced` has a real race
in it:

```kotlin
@Query("UPDATE transactions SET pending_operation = NULL, remote_revision = :remoteRevision WHERE id = :id")
suspend fun markSynced(id: String, remoteRevision: Long)
```

Between `pendingChanges()` reading a row and `markSynced` clearing its flag, the
user can edit that row. The edit bumps `local_revision` and re-sets
`pending_operation = 'UPSERT'` correctly — and then `markSynced` clears it
unconditionally. The row is now dirty, unflagged, and will never be pushed
again. The edit is not lost from the local database, which is what makes it hard
to notice; it is lost from every *other* device, permanently, until something
unrelated touches the row.

The fix is the shape this codebase already uses for exactly this — put the
condition in the `WHERE` clause, so the guarantee doesn't depend on the caller:

```kotlin
@Query(
    """
    UPDATE transactions
    SET pending_operation = NULL, remote_revision = :remoteRevision
    WHERE id = :id AND local_revision = :expectedLocalRevision
    """,
)
suspend fun markSynced(id: String, remoteRevision: Long, expectedLocalRevision: Long): Int
```

Zero rows updated means the row moved under the push, and it stays pending for
the next round. Same pattern as `applyRuleCategory` and `softDeleteBatch`, same
"report the honest count" return value.

There is a second, quieter gap in the same area. `OfflineFirstTransactionRepository
.upsert` — the create path — writes through `TransactionDao.upsert`, and
`toEntity` leaves `pendingOperation` at its default of `null`. So **a
newly-created transaction is invisible to `pendingChanges()` and would never be
pushed at all.** `update`, `softDelete`, `undoSoftDelete` and `softDeleteBatch`
all set it; the budget, rule and category repositories all set it in their own
`upsert`; transactions are the odd one out.

*Corrected while fixing it in slice 3.* This paragraph used to justify the
omission by saying `upsert` "is also the path CSV import and seeding use,
where setting it per row would be wrong — an import of 400 rows should
enqueue one batch, not 400 individually-flagged rows." That is not true and
was checkable: `TransactionDao.upsert` has exactly one caller,
`OfflineFirstTransactionRepository.upsert`, which in turn has two,
`TransactionFormViewModel`'s create branch and `DebugSeeder`. CSV import goes
through `importBatch` — a different DAO method, a different insert
strategy — and category seeding is a different DAO entirely. There was no
tension to resolve, only a path that had been missed, and an invented reason
kept it missed for a milestone.

The concern the invented reason was reaching for is still real and still
belongs to slice 5: enqueueing should ultimately be derived from
`local_revision` versus the shadow's `remote_revision`, with
`pending_operation` kept as the tombstone marker it is genuinely needed for,
because deriving "dirty" from data the writes already maintain beats a fifth
write path that has to remember a flag. Until that exists, four write paths
that agree beat three that agree and one that is silently different.

**A third one, found while building slice 1.** Every repository's `upsert`
reads the existing row with a `getById` that filters `deleted_at IS NULL`, then
derives the new `local_revision` from it. Upserting an id that is tombstoned
therefore finds nothing, resets the revision to 1 and drops `remote_revision`
— a resurrected row that claims to be brand new. `categories` and
`category_rules` both have this shape; only `BudgetDao.findActive` sidesteps
it, deliberately, because a new budget for the same category and month gets a
new id rather than reoccupying the old one. It is unreachable today, since
nothing re-creates a deleted row under its old id, and it stops being
unreachable the moment sync can hand a repository an id it has seen before. The
fix belongs with the other two, in slice 3: revision bookkeeping reads the row
regardless of its tombstone, because a revision is a fact about the row, not
about whether it is currently visible.

*Reachable as of slice 2, and fixed in slice 3.* Natural-key budget ids mean
recreating a deleted budget revives that very row (§3.2), so this stopped being
theoretical one slice before it was fixed. The fix is a narrow projection,
`RowRevision`, read by a `revisionOf(id)` query with no `deleted_at` condition
on any of the four DAOs — deliberately not a general "get including deleted",
which could be mistaken for something to display. `created_at` stays on the
tombstone-filtering read on purpose: recreating a budget is the user creating a
budget, whatever the row underneath has been through.

**All three fixes are in code nothing calls yet, so the tests are the whole of
what stands between them and the first sync that reaches them.** Each was
written test-first and then watched to fail with the fix removed. The
`markSynced` one is worth recording: Room's own processor rejects dropping the
guard while the parameter is still declared — `[ksp] Unused parameter:
expectedLocalRevision` — so the guard has an accidental second layer under
it. Neutering the condition instead (`AND (:expectedLocalRevision >= 0)`) is
what a real regression would look like, and that is what the test catches:
`expected: 0, but was: 1` on the return value, meaning the UPDATE matched the
row it should have refused.

**A fourth one, found while building slice 5's dirty-row derivation.**
`categories` had no `updated_at` at all — it predates sync and never needed a
timestamp before. `SyncOp.updatedAt` is non-nullable and §5.5's tiebreak
genuinely needs a real one; `local_revision` is not a stand-in for it, because
it is a per-device counter, not a comparable wall-clock value, and comparing
two devices' counters as if they were would make the tiebreak's outcome
depend on how many *local* edits each device happened to make rather than
which edit came later. Fixed in `MIGRATION_6_7`, additive like `MIGRATION_3_4`
was for the other three sync columns, with pre-existing rows defaulting to
`0` rather than `now()` — the safe direction to be wrong in, since `now()`
would let every untouched category beat a genuine earlier edit it happens to
race in a tiebreak, while `0` only ever loses one.

**A fifth one, found while building slice 5c's apply step, and the one that
would have broken push-back silently rather than loudly.** `local_revision`
is a per-device counter — device A's copy of a row edited fifty times and
device B's copy edited three times both started at 1 and climbed by one per
edit, on scales that share nothing. `dirtyRows` compares `local_revision`
against `sync_shadow.remote_revision` *for the same device*, which is safe on
its own. But applying a remote op overwrites that shadow with a number from
the *other* device's scale — and the naive move, bumping `local_revision` by
one the same way every other write path does, would then compare B's small
counter against A's large one on every future dirty check. If A's revision
for a row ever exceeds whatever B's `local_revision` reaches next, B's own
genuinely new edits to that row look "already synced" and stop being pushed —
permanently, with nothing failing loudly, which is exactly the shape of bug
§4.3 exists to catch and would not have caught this one.

The fix is the move a Lamport clock makes on receiving a message: rebase to
`max(what this device had, what it just learned)`, and add one only if this
device is contributing something the remote does not already have (i.e. the
merge outcome differs from the op's own payload). That keeps `local_revision`
always at least as large as every revision this device has ever observed from
anyone, so the comparison `dirtyRows` relies on stays valid regardless of how
far two devices' counters had drifted before they met. Implemented in
`SyncApplier.decide` (`:core:data`), with the reasoning above repeated at the
call site rather than only here — the same "restate why, not just what"
argument this document has made for every other guard in a `WHERE` clause.

One more decision made in the same slice, smaller but worth recording: a
tombstone applied from a remote op sets `deleted_at` to *this device's* apply
time, not to any value carried on the wire — `SyncOp` has no deletion instant,
the same way it has no `created_at` (see the correction in §4.1). Read the
same way: not a fact about when the row died, but about when this device
found out, which is all sync ever promises for either timestamp.

---

## 5. Conflict resolution

### 5.1 What the M0 sketch gets wrong

```
amount, date, description  -> remote wins (they came from the bank)
categoryId                 -> whichever side has categoryLockedByUser
notes                      -> merge, newest first, if both changed
deletion                   -> deletion always wins over an edit
```

Line by line:

**"Remote wins (they came from the bank)" is false twice over.** The bank never
talks to this app. There is no bank-side anything: a transaction's amount, date
and description are produced by a *device*, either by a user typing them or by
that device's CSV importer interpreting a file. And two devices can interpret
the same file differently — csv-import-design §2 spends a page on ambiguous date
formats precisely because `03/04/2026` resolves to two different real dates, and
which one a device picked depends on what the user was shown and tapped. "Remote
wins" would let whichever device synced second silently overwrite the other's
date. There is no authority here. There are two peers.

**The category rule is half right and stated backwards.** `categoryLockedByUser`
is a real tiebreaker, but the rule as written ("whichever side has it") has no
answer for the case where both sides have it, which is the case that actually
needs a rule. §5.4.

**"Notes merge" names an outcome, not an algorithm.** §5.5.

**"Deletion always wins over an edit" is the one that is actively wrong**, and
it contradicts a decision this repo has already made elsewhere. §5.3.

The parts of the M0 sketch that are right and worth keeping: per-field rather
than per-row resolution (the whole premise), and `resolve(local, remote, base)`
having a `base` at all. `FieldResolution`'s four cases survive; `USER_PROMPT` is
never used by the resolver itself and instead becomes the "record it and let the
user reverse it" path in §5.6, which is strictly better than a modal dialog
appearing because a background sync ran.

### 5.2 The frame: causality first, policy only for genuine ties

With the shadow table (§4.1) every merge starts by classifying, not by applying
policy:

| local vs base | remote vs base | outcome |
|---|---|---|
| unchanged | unchanged | nothing to do |
| changed | unchanged | **fast-forward local** — push it, no merge |
| unchanged | changed | **fast-forward remote** — apply it, no merge |
| changed | changed | genuine concurrent edit → per-field merge below |

Most of what a naive design treats as a conflict resolves in the middle two
rows, without any policy being consulted at all. That is the single biggest
correctness win in this design and it comes entirely from storing the base.

For the fourth row, the merge is **per field**, and the classification repeats at
field granularity: a field only reaches its policy if *both* sides changed it
away from the base. If the user edited the amount on the phone and the notes on
the tablet, both edits survive; no policy fires.

### 5.3 Deletion versus edit

The concurrent case — A deletes, B edits, neither had seen the other — has no
correct answer, only a choice, and the choice should be decided by which mistake
is recoverable.

- If deletion wins, B's edit is gone. Not gone-and-recoverable: the row is
  tombstoned on both devices and the edited values are not stored anywhere the
  user can reach.
- If the edit wins, the row reappears on A. The user who deleted it sees it come
  back and deletes it again. Annoying; costs one tap; loses nothing.

**Recommendation: a concurrent edit beats a concurrent delete.** A delete that is
*causally after* the edit (its base already includes the edit) still wins — that
is not a conflict at all, it is the second row of the table in §5.2.

This is also the answer to "what if the edit came after the delete on wall-clock
time?": **wall clock is never consulted for this.** Two devices that were both
offline have no shared clock, their clocks can be arbitrarily skewed, and
`updated_at` is a value either device could have produced at any moment. Ordering
by it would make the outcome depend on a number neither user can see and neither
device can trust. Causality — did this side's base already contain that change —
is knowable and is what gets used.

There is a second, better reason to prefer edit-over-delete here, and it comes
from a decision this repo has already made. `TransactionDao.update` clears
`import_batch_id`, and `softDeleteBatch` matches on it, so **a hand-edited row
leaves its import batch and batch undo no longer reaches it** — the doc comment
on `update` states that explicitly as the whole rule for what an edited row does
on batch undo. "Deletion always wins" would break that rule the moment the edit
and the undo happened on different devices: device A undoes a 400-row import,
device B had edited three of those rows, and edit-loses would delete exactly the
three rows the local rule protects. Edit-over-delete makes batch undo behave the
same way across devices as it already does on one. That's not a coincidence; it
is the same asymmetry (a re-delete is cheap, a lost edit is not) showing up
twice.

Rule-driven changes are excluded from this: a category set by
`applyRuleCategory` is not a user edit and does not resurrect a deleted row.
The resolver can tell, because a rule's write touches exactly one field.

**Corrected while building slice 6.** `resolveDeletionVsEdit`'s rule-guess
check (`changedFields == setOf(CATEGORY_ID)`) has an unhandled third case:
`changedFields` empty. That is not a rule guess and it is not a genuine edit
either -- it means the "edited" side has not touched the row *at all* since
the shared base, which is §5.2's second row (this side unchanged, the other
changed), not a delete-versus-edit conflict. The code before this fix treated
"unchanged" and "genuinely edited" identically (anything that wasn't exactly
`{category_id}` kept the edited payload), so a row a device had already
fast-forwarded to some earlier state, with no further local edit, resurrected
itself the next time that same device's own untouched copy was compared
against a delete. Two of the two-device harness's scripted scenarios hit this
directly: an edit that syncs and is later followed by a *causally-after*
delete on the other device (§10.2 scenario 5) came back to life, and deleting
a category with a live transaction in it, then restoring the category,
resurrected the category on the peer that had never touched it. Fixed by
splitting the check: `changedFields.isEmpty() || changedFields ==
setOf(CATEGORY_ID)` both let the deletion win; only a genuinely non-empty,
non-rule-guess set of changed fields keeps the edited payload.

### 5.4 `categoryLockedByUser`, and the fourth enforcement layer

budgets-design §1.4 gives the lock three guards: the candidate query
(`ruleCandidates` has `category_locked_by_user = 0` as a *condition*, with
deliberately no parameter to switch it off), `applyRules`' own `filterNot`, and
`applyRuleCategory`'s `WHERE` clause. The task asks where sync enforces it and
what stops a sync path forgetting. The honest answer is that **none of those
three guards apply to sync at all**, and the fourth layer is different in kind
from the other three.

Why none of them apply: all three are guards on a *rule* setting a category. Sync
is not a rule. Sync's write is a whole-row upsert of a merged value; it has no
`WHERE category_locked_by_user = 0` to hang the guard on, because the row it
writes may legitimately change the lock flag itself (the user locked it on the
other device — that's a change that has to propagate).

And sync should not be blocked by the lock, which is the part that takes a
moment. The lock means "a rule must not overwrite this choice." A category
arriving from the user's other device, where they set it by hand, is not a rule;
it is the same person's intent. Refusing it would be as wrong as accepting a
rule's guess over it.

So the lock becomes a **tiebreaker on the `category_id` field**, and it is the
resolver that enforces it:

| local | remote | result |
|---|---|---|
| locked | unlocked | local's category — a hand choice beats a rule's guess |
| unlocked | locked | remote's category, and the lock comes with it |
| locked, locked, same category | — | no conflict |
| locked, locked, different | | genuine user-vs-user; falls to §5.5's tiebreak, **and is recorded** |
| unlocked, unlocked, different | | two rule guesses disagree; tiebreak, recorded |

The case that makes this the right shape rather than an arbitrary one: device B
runs its rule engine over a transaction that device A locked by hand, before B
has synced. B's copy has `category_locked_by_user = 0`, so **B's three guards all
correctly permit the rule to fire** — nothing is broken, B simply doesn't know
yet. The merge is the first moment the two facts meet, and the first row of the
table above is what makes A's hand choice survive. The lock's guarantee is
therefore not enforced *before* sync writes; it is enforced *by* what sync
writes.

**What stops a sync path forgetting?** Two things, and neither is a convention:

1. **There is no `applyRemote(entity)`.** The sync engine has no write path that
   takes a row off the wire and puts it in the database. The only way a remote
   value reaches Room is as the return value of `resolve(local, remote, base)`.
   A future contributor who wants to shortcut that has to add a DAO method to do
   it, which is a visible thing to review, rather than calling an existing one
   that happens to be too permissive.
2. **A field-coverage test that fails the build when a column has no policy.**
   The resolver declares its policy as an explicit map from field name to
   `FieldResolution`; a JVM unit test reflects over each synced entity's
   properties and asserts every one of them appears in that map. Adding a column
   to `TransactionEntity` without deciding how it merges does not silently
   default to "remote wins" — it fails a test with the column's name in the
   message. That test is cheap, it is the only mechanism here that scales to
   columns nobody has thought of yet, and it is the direct answer to the
   question.

### 5.5 Notes, and the one place wall clock is used

Two divergent texts don't merge without a rule, and a bad rule silently mangles
a user's words. Options considered and rejected: a three-way line merge (diff3
on a one-line note produces conflict markers, which are hostile in a UI field
and meaningless in a finance app); last-write-wins (loses one side silently,
which is the whole thing this milestone exists to avoid).

**Proposed rule, in order:**

1. One side equals the base → take the other. (This is §5.2's classification and
   handles the overwhelming majority.)
2. Both changed, and one contains the other as a substring → take the longer.
   This catches the common real case: the user appended to the same note on both
   devices, and one append is a superset of the other.
3. Otherwise → **keep both**, concatenated, newest-`updated_at` first, separated
   by a marker line, and record a conflict (§5.6).

Nothing is ever deleted by rule 3, which is the property that matters. What it
gets wrong, stated plainly: step 2 is a heuristic and will pick the wrong side
if the user deliberately *shortened* the note on one device and appended on the
other — the append wins and the shortening is undone. And step 3's output is
ugly, and gets uglier if the same note conflicts repeatedly, since each
concatenation becomes the next base. Both are visible to the user in a field
they can edit, which is why an ugly, reversible answer is preferred to a clean,
lossy one.

Step 3 orders by `updated_at`, and it is worth naming that this is the **only**
place in this design where wall-clock time is consulted, and it is used as a
tiebreak on presentation order, never as an ordering primitive for causality.
The same applies to the genuine same-field ties in §5.4: newest `updated_at`
wins, ties broken by `deviceId` lexicographically. That is deterministic (both
devices see the same two timestamps and compute the same answer, so it converges)
and it may well be wrong about which edit really came later if a clock is skewed.
It is used only where every available answer is equally defensible, and every
such resolution is recorded.

### 5.6 What the user sees

Silently is not an option in this app. csv-import-design refuses to guess an
ambiguous date, refuses to guess an encoding, and shows failed rows as a count
rather than burying them; budgets-design surfaces the uncategorised total rather
than folding it in. A background process that quietly changes the amount on a
transaction would be out of character even if it were usually right.

The proposal is not a dialog. A modal appearing because a background sync ran is
worse than useless — the user has no context for it and no way to judge it.
Instead:

```
sync_conflicts
  id                 TEXT PK
  table_name         TEXT NOT NULL
  row_id             TEXT NOT NULL
  field              TEXT NOT NULL
  resolved_at        INTEGER NOT NULL
  chosen_value       TEXT NOT NULL      -- JSON, the value that won
  discarded_value    TEXT NOT NULL      -- JSON, the value that lost
  reason             TEXT NOT NULL      -- which rule fired
  acknowledged_at    INTEGER            -- null until the user has seen it
```

Written for every resolution that had to consult a policy — that is, only the
fourth row of §5.2's table, per field. Fast-forwards are not conflicts and are
not recorded; recording them would produce noise proportional to sync volume and
train the user to ignore the list.

Three consequences worth stating:

- **Every policy decision becomes reversible.** `discarded_value` means no merge
  rule has to be *right*; it has to be *recorded*. That is the thesis this whole
  section rests on, and it is what makes the arbitrary tiebreaks in §5.5
  acceptable rather than alarming.
- **The recording has to be in M4a even if the screen is not.** A discarded value
  cannot be reconstructed later. The table and the writes are non-negotiable; a
  UI that displays and restores them can slip. Same argument csv-import-design §11.1
  makes for `importBatchId` and budgets-design §4.1 makes for putting sync
  columns on tables before sync exists.
- **It needs a bound.** An unacknowledged conflict list that only grows is a
  second unbounded table next to the tombstones (§7). See below.

Surfacing: a count on the Settings sync row ("3 conflicts resolved — review"),
and a marker on the affected transaction row in the list. Not a notification —
budgets-design §2.5 already declined to add notification infrastructure for the
over-budget case, and nothing here is more urgent than that was.

#### Who reads it, and what removes a row

A table that is only written to is a table nobody looks at, so both halves have
to be named.

**Read by**, in the order the readers arrive:

- The supersede check inside `SyncConflictDao.record`, and the horizon sweep.
  Both exist from slice 3b.
- The count on the Settings sync row, and the conflict list — slice 8, **done**:
  `SyncConflictRepository.observeConflicts`/`observeUnacknowledgedCount`,
  `:feature:settings`'s `SettingsScreen`.
- **The restore path** — slice 8, **done**, and the one that makes
  `discarded_value` a column rather than decoration. A discarded value nothing
  can put back is a log entry, not a reversal, and this section's entire
  argument is that the merge rules need not be *right* if they are reversible.
  `SyncConflictRepository.restore` reads the row fresh, compares its live field
  value against `chosen_value`, and refuses -- `RestoreOutcome.VALUE_CHANGED_SINCE`,
  surfaced in the screen rather than swallowed -- if something has edited that
  field again since the conflict resolved; restoring anyway would silently
  overwrite a newer value with one that is now at least two edits stale, the
  same cost superseding already names above. `ROW_GONE` covers the row being
  missing or tombstoned. A successful restore acknowledges the conflict (list
  (1) above) rather than deleting it, so it still ages out on the ordinary
  horizon sweep instead of vanishing with no trace it happened.

For the period before slice 8 landed, there was no user-facing reader, which was
the same shape of risk as the `h1:` prefix in slice 2: a marker that is written
but never branched on is not load-bearing. What made it acceptable was that the
asymmetry ran the other way — a discarded value cannot be reconstructed after
the fact, while a screen can be built at any time, so recording first and
reading later was the only order that worked at all. Same argument
csv-import-design §11.1 makes for `import_batch_id` and budgets-design §4.1 for
putting sync columns on tables before sync exists.

**The screen slice 8 built still has no live data path.** `sync_conflicts` is
written from exactly one place — `SyncApplier`'s conflict-recording path,
driven by `ConflictResolver` — and nothing in the app calls it. `SyncEngine`
has no caller anywhere (the comment at the top of `SettingsViewModel` says so
directly), because M4a stops before M4b's transport exists. There's also no
way to hand-seed the table to see the populated state for real: the release
build stays debug-signed (see the convention plugin comment) but is not
`isDebuggable`, so `run-as`/`adb shell` writes into its Room database aren't
possible either, and the debug build has no sync running to produce a genuine
row. `SettingsScreenTest` and the `SettingsViewModel` tests exercise the
populated and restore-flow states entirely against `FakeSyncConflictRepository`
— real behaviour, never real data. That's fine for M4a, whose deliverable is
the convergence suite, not this screen (§2) — but it means **M4b inherits an
unusual bug surface**: the first conflict a real two-device sync ever produces
will also be this screen's first render against a non-fake row, seen by nobody
until then. Whatever the fakes didn't think to cover — a `chosenValue`/
`discardedValue` shape the resolver never actually emits, a `reason` string
that doesn't wrap where the fakes' did, a `table_name` the tests never fed it —
surfaces for the first time on that render, not before.

**Three things delete a row. Nothing else does.**

1. **Superseding.** At most one *unacknowledged* conflict per (table, row,
   field): recording a new one drops the older. This is the bound. Without it,
   two devices that keep disagreeing about one field leave a row per sync — a
   table growing with sync volume rather than with anything the user did, which
   is what §7 refuses for tombstones. The cost belongs in writing rather than in
   a footnote: the superseded `discarded_value` was the only copy of that value
   and it is gone. What is lost is a value from a merge that has since been
   merged over again, so restoring it would put back something two edits stale.
   That makes the trade acceptable, not free.
2. **The horizon.** An acknowledged conflict is deleted 90 days after
   `acknowledged_at`, on the same sweep as the tombstones (§7). An
   **unacknowledged one never ages out.** Deleting one would discard a losing
   value before anybody saw it, which is the single thing this table exists to
   prevent, and its growth is already bounded by (1): a user who never opens the
   screen accumulates one row per field they have genuinely edited on two devices
   at once, not one per sync.
3. **The row dying.** When a tombstone crosses the horizon and its row is
   hard-deleted, its conflicts go with it. No foreign key does this —
   `sync_conflicts` names its parent table in a column — so it is an explicit
   call in the sweep. What it prevents is a list entry that can only render as
   pointing at nothing.

`acknowledge` is a compare-and-set (`... AND acknowledged_at IS NULL`) for the
same reason `markSynced` is one: `acknowledged_at` is the clock rule 2 reads, so
an unguarded UPDATE would push a conflict's expiry forward every time the screen
was opened, and nothing acknowledged would ever expire.

---

## 6. Interactions with what already exists

### 6.1 `import_batch_id` and batch undo across devices

The scenario the task names: a batch imported on device A syncs to B, then A
undoes it.

The mechanics work, and mostly for free. `import_batch_id` is a plain column and
rides along in each row's payload, so B's copies carry the same batch id. Undo on
A is `softDeleteBatch`, which tombstones each row individually — 400 tombstones,
400 delete ops, one batch. B applies them as ordinary deletions. B can also
undo the same batch itself, since it has the same ids; the second undo tombstones
nothing (`deleted_at IS NULL` guards it) and reports 0, which is the honest count
`softDeleteBatch` is already built to return.

The interesting part is the interaction with §5.3, and it is the argument that
decided §5.3 rather than a consequence of it: if B hand-edited three of those
rows, `update` cleared their `import_batch_id`, and locally those three are
already excluded from undo by design. Across devices, edit-over-delete produces
exactly that outcome — the three edited rows survive A's undo and everything else
goes. "Deletion always wins" would have deleted them, i.e. it would have made
batch undo mean one thing on one device and something else across two.

One case this design does **not** fully handle, named rather than hidden: A
imports a batch and undoes it entirely before ever syncing. The 400 rows were
created and tombstoned locally. Under §3.1's content-derived ids, those
tombstones are for ids B would generate too — so if B later imports the same
statement, B creates rows that A already has tombstones for, and A's tombstones
win (they are causally unrelated, and a tombstone versus a create for the same id
resolves as delete-versus-create, not edit-versus-delete). B's import silently
vanishes on the next sync. This is rare and it is the price of content-derived
ids; the mitigation is that an import whose rows collide with existing tombstones
should surface that in the import result ("12 rows were previously deleted and
were not re-added"), which is the same "show it rather than bury it" shape
`ImportResult.duplicatesSkipped` already has. It is a slice-9 item, not a reason
to reject §3.1.

**Design, since it's now due (§13 slice 9b).** `duplicatesSkipped` cannot
distinguish the two cases today, and the reason is checkable rather than
assumed: `TransactionDao.countExistingHashes` has no `deleted_at IS NULL`
condition —

```sql
SELECT content_hash, COUNT(*) AS existing_count FROM transactions
WHERE content_hash IN (:hashes)
GROUP BY content_hash
```

— so a tombstoned row's hash consumes the same quota unit as a live one in
`importBatch`'s count-aware filter (§4). A row that collides with a tombstone
is filtered out of `fresh` before insert is ever attempted, and is counted as
an ordinary duplicate. **That is wrong on a single device with no sync anywhere
near it**: a user deletes a coffee, re-imports the statement a month later
meaning to get it back, and the import result says "1 duplicate skipped" —
indistinguishable from the case where the transaction was never deleted. Sync
makes it worse (above) but does not create it.

The fix splits the quota by status rather than only counting it.
`countExistingHashes` becomes `existingRowsByHash`, returning
`(contentHash, id, deletedAt)` rather than a bare count — the DAO already has
to know which specific rows it is counting to answer this; a count alone
throws that away. `importBatch`'s quota consumption tags each unit taken as
`LIVE` or `TOMBSTONED` (which specific row within a hash's matches gets tagged
first is arbitrary here, the same way it's already arbitrary which existing
row a duplicate "matches" today — nothing in this design needs to prefer one
tombstoned row over another). The result carries two counts instead of one:
`duplicatesSkipped` narrows to mean "collided with a live row," and a new
`previouslyDeletedSkipped` counts the tombstoned collisions:

```kotlin
data class ImportResult(
    val imported: List<Transaction>,
    val duplicatesSkipped: Int,
    val previouslyDeletedSkipped: Int,
    val failedRows: List<FailedRow>,
    val batchId: String,
    val autoCategorisedCount: Int = 0,
)
```

The import result screen gains one line, shown only when the count is
nonzero, in the same register as the existing summary: "12 rows were
previously deleted and were not re-added." **No restore affordance rides
along with it.** Offering one would mean guessing at intent the app has no way
to check — a row a user deliberately deleted and a row they want back produce
the identical signal, a content-hash match against a tombstone — and
`duplicatesSkipped` doesn't get an undo button either. Counting and naming it
is the whole of what this line does; a user who wants the row back already has
the transaction list's own restore path for a specific row, once they know to
look for it.

This is entirely local — no transport, no `SyncEngine` — and testable in
`:core:importer` and `:core:database` today, so it does not have to wait on
the rest of M4b. It is grouped with the transport slices in §13 because the
scenario that surfaced it (§6.1) is a cross-device one, but nothing about the
fix itself is.

### 6.2 Does M4 trip budgets-design §2.2?

§2.2's condition, exactly: the `combine` tearing in `observeMonthlyBudgets` is
tolerable **only** because the sole consumer renders the pair and conflates a
superseded value before drawing it. It expires "the moment anything *acts* on the
pair rather than displaying it."

**M4 as scoped does not trip it.** Sync writes to `transactions`; it never reads
`observeMonthlyBudgets`. No new consumer of that flow is added. The condition is
about consumers, not about write volume.

Three qualifications, because the answer is "no, provided":

1. **M4 must not add budget alerts.** A "you crossed your Food budget" notice
   evaluated against a torn frame fires on a number that was never true, or
   misses a crossing entirely, and a notification once sent is not unsent by the
   next frame settling. §2.2 names this as the case, and sync makes it more
   tempting, not less — "notify me when my other device's spending pushes me over"
   is a natural-sounding M4 feature. It is a prerequisite-violating one. If it is
   wanted, the single-query redesign in §2.2 is scoped as part of it.
2. **Sync batches must be applied in one Room transaction.** A batch of 200
   remote transactions applied as 200 separate `UPDATE`s invalidates `transactions`
   200 times, and every observer of `observeBudgetsWithSpend` and
   `observeUncategorisedSpend` re-queries 200 times, each with its own torn
   window. Applying the batch inside one `@Transaction` — the same reasoning
   `importBatch` and `applyRuleCategories` already use — makes Room's
   invalidation fire once at commit. This makes tearing *less* frequent under
   sync than it would otherwise be, not more.
3. **The transient is now reachable while the screen is idle.** Before M4, the
   only writes to `transactions` came from a user action on some screen. After
   M4 a background sync can write while the budgets screen is open and untouched.
   That does not change the analysis — 2.66 ms against a 16.7 ms frame, conflated
   before composition reads it — but it does change the *frequency*, and the
   measurement in `BudgetTotalsTransientTest` was taken under user-driven writes.

   **Re-measured under sync-driven writes** (`SyncDrivenBudgetTotalsTransientTest`,
   `:core:data`; unassigned across slices 6 and 7, closed out afterward rather
   than left unowned): the same write, routed through `RoomSyncApplier.apply`
   -- one remote `SyncOp`, applied inside sync's own `withTransaction` -- instead
   of a direct DAO call from a screen. 30 runs each direction, one emulator
   (Pixel_9_Pro_XL API 36): the forward direction (uncategorised → budgeted) tore
   on 19/30 runs (2 double-counted, 17 counted in neither); the reverse direction
   tore on 21/30 runs (17 double-counted, 4 counted in neither). Both torn shapes
   occur in both directions, the same finding `BudgetTotalsTransientTest` already
   made under user-driven writes -- which side re-queries first is still not
   ordered by anything the write path controls. All 60 runs settled on the
   correct pair with exactly one transition per side; none violated the three
   order-independent properties. Applying through the sync path adds a merge
   decision and a full-row upsert ahead of the write, but that work happens
   before the transaction commits, so Room's invalidation still fires once from
   one write, same as the direct-DAO path -- nothing here found a second commit,
   an oscillation, or a wrong settle. The higher tear rate than intuition might
   expect is not compared against a same-sample-size number for the direct-DAO
   path, because `BudgetTotalsTransientTest` runs each direction once per CI
   invocation rather than in a loop; a frequency comparison between the two
   paths would need that test re-run at the same count first, which is out of
   scope here.

### 6.3 The content hash

Covered in §3.1: it neither helps nor hurts today because sync never consults it,
and D4 option (b) is the proposal that makes it help. One thing it must **not** be
used for is a post-merge de-duplication pass keyed on hash presence — that is the
presence-versus-count bug csv-import-design §4 already fixed once, and running it
over merged data would silently delete legitimate identical-tuple transactions
that the count-aware fix specifically preserved.

### 6.4 `Transaction.merchant` — budgets-design D6 comes due

D6 resolved that `merchant` stays, with a deadline: if merchant extraction hasn't
been built by the end of M4, drop the column then, "folded into whatever table
rebuild M4's sync work needs anyway, never as a rebuild of its own."

**M4 as designed here needs no table rebuild.** Every schema change in §9 is
`ALTER TABLE ADD COLUMN` or `CREATE TABLE`; the id rewrites in §3 are `UPDATE`s,
not rebuilds. So the rebuild D6 planned to ride along on does not exist, and the
deadline arrives with no free vehicle. That has to be decided rather than
rediscovered at the end of M4 — see D11. It also gets slightly more expensive
under sync: a column that is always null still travels in every payload and still
needs a merge policy (§5.4's coverage test will demand one).

### 6.5 Rule priority

`category_rules.priority` is a position in a user-ordered list. Reordering on two
devices concurrently and merging per-row produces a priority set neither user
arranged — duplicated values, gaps, an order that matches nobody's intent.

The saving grace is already in the design: budgets-design §1.5 specifies
evaluation in `(priority, id)` order, a *total* order, with ties broken by id. So
a garbled merge is still **deterministic and convergent** — both devices agree on
the resulting order, and rule evaluation stays well-defined. What is lost is
intent, not correctness.

The proper fix is a fractional index (an ordered string key, so an insertion
between two rules needs no renumbering and concurrent reorders merge sensibly).
It is real work for a list that realistically holds a dozen rows. **Recommendation:
accept the garbling**, note it in the rules screen's reorder affordance, and
revisit if rules ever become numerous. D10.

### 6.6 Other existing assumptions sync inherits

- **`accountId` is the hardcoded constant `"acct-1"`** (`TransactionFormViewModel`),
  and there is no `Account` table. Sync works only because both devices use the
  same constant. That is true today and stays true; it is worth one comment where
  the id is used in `contentHashOf`, because if accounts ever become real, an
  account id generated per-device would break §3.1's content-derived ids on the
  first character.
- **Single currency.** `BudgetDao.observeBudgetsWithSpend` deliberately has no
  currency condition on its join because every writer produces `INR`. Sync does
  not change that and must not be the thing that introduces a second currency by
  accident.
- **`DebugSeeder`** runs in debug builds only and writes through the ordinary
  repositories, so its rows are ordinary rows and would sync. That is correct for
  a debug build and wrong if a debug device is ever paired with a real one. The
  sync settings screen is debug-visible anyway; one line in D2's setup notes,
  not a design problem.

---

## 7. Tombstones

They are never cleaned up today. Nothing deletes a row where `deleted_at IS NOT
NULL`, and until now nothing needed to — the table grows by however many
transactions a user deletes, which is small. Batch undo changes the arithmetic
(one undo of a 400-row import is 400 tombstones) but not the order of magnitude.

Under sync a tombstone cannot simply be deleted, because a device that has been
offline since before the deletion would see a row it has and the remote doesn't,
and would helpfully re-upload it. Resurrection-by-cleanup is the standard failure
here.

**Proposed: a tombstone horizon.**

1. Tombstones older than **90 days** are hard-deleted locally, and their ops are
   dropped from the remote log during compaction (§8.3).
2. The remote carries a `horizon` watermark: the oldest point from which the op
   log is still complete.
3. A device whose `lastSyncCursor` is older than the horizon **must not do an
   incremental pull.** It takes the full-reconciliation path instead.

**Full reconciliation** is: fetch the current snapshot, compare it to the
local table row by row, and for any local row absent from the snapshot, decide by
`local_revision` versus shadow whether it is a genuine local creation (push it) or
a row deleted remotely while this device was away (delete it locally). It is
slower and it is the only correct answer for a device that has missed history. It
is also needed anyway — a brand-new device does exactly this — so it is not extra
machinery invented for the horizon.

90 days is a guess, stated as one. The number that matters is "longer than any
device is plausibly offline," and a tablet in a drawer for a season is the case it
is sized against. Making it a constant in one place, and making the
over-the-horizon path a tested code path rather than a theoretical one, matters
more than the value. D8.

`sync_conflicts` rows that have been acknowledged age out on the same horizon
(§5.6).

**Built in slice 7.** M4a has no real compaction (that is M4b's periodic job,
§8.3, slice 9) and no real Drive transport to compact, so this section's
"remote carries a horizon watermark" needed a concrete shape before any of it
could be a tested path rather than a description. What got built:

- `SyncTransport` gained one new method, `snapshot(): RemoteSnapshot` --
  `RemoteSnapshot(horizon: Map<deviceId, seq>, rows: List<SnapshotRow>)`,
  `:core:model`. `horizon` is empty for a transport nothing has ever
  compacted, which is the safe default every implementation starts from:
  an empty horizon can never be "behind", so nothing reconciles against it
  by mistake.
- Triggering compaction is deliberately **not** part of `SyncTransport`.
  On Drive it will be a periodic maintenance job (slice 9) that some device
  runs opportunistically, not something the engine asks for. `InMemoryTransport`
  gets a `compact()` method that is not part of the interface, exists only so
  a test can put a device behind the horizon on purpose, and folds every
  batch pushed so far into a `RemoteSnapshot` the same way `RoomSyncApplier`
  folds a batch -- newest op per row wins, tombstones excluded from the
  result rows -- then drops every batch it just folded in.
- `SyncEngine.sync` calls `transport.snapshot()` on every cycle and compares
  its own per-peer cursor against `horizon`. Behind, for any peer, means
  incremental `pull` is skipped for that cycle in favour of
  `SyncApplier.reconcile(snapshot, deviceId)`, and the cursor is advanced to
  `horizon` afterward so the next cycle pulls normally again. Fetching the
  full snapshot on every cycle is the simple choice for a free, in-memory
  fake; a real backend paying a network round trip for this on every sync
  would likely want a cheaper way to learn just the horizon first -- not a
  problem this engine has evidence for yet, so not built.
- `SyncApplier.reconcile` applies every row the snapshot still knows about
  exactly like an ordinary pulled op, reusing `apply`'s own merge machinery
  (the reasoning this section already gives: a fresh install's first sync is
  the same shape). For a local row **absent** from the snapshot, the exact
  rule this section left as "decide by local_revision versus shadow": no
  shadow, or `local_revision` greater than the shadow's `remote_revision`,
  means either a genuine local creation remote has never seen or a local
  edit newer than what was last agreed -- edit-over-delete (§5.3) one
  horizon later, so the row survives untouched (its stale shadow, if any, is
  forgotten so it is pushed as a fresh creation rather than compared against
  a base remote can no longer produce). Otherwise -- nothing beyond what was
  already agreed -- the row is hard-deleted, and its `sync_shadow`/
  `sync_conflicts` rows go with it.
- `TombstoneReaper` (`:core:data`) is the local hard-delete half, run from
  `SyncEngine.sync` once per cycle rather than a periodic worker -- there is
  no WorkManager wiring yet (slice 9) to run it any other way, and this is
  what makes the horizon an exercised path today. It reaps
  `SyncTable.entries` **reversed** -- every child table before
  `CATEGORIES` -- on purpose: `budgets.category_id` and
  `category_rules.category_id` are `ON DELETE CASCADE`, and
  `CategoryDao.softDeleteUserCategory` always tombstones a category and its
  budgets/rules at the same instant, so by the time a category is old enough
  to reap, anything that would cascade from it has already been reaped (and
  already forgotten from `sync_shadow`/`sync_conflicts`) on its own account.
  Reaping in the other order would let the category's hard delete cascade
  first, leaving the budget/rule's shadow and conflict rows pointing at
  nothing. `TombstoneReaperTest` (`:core:data`, real Room) asserts this
  directly rather than trusting the ordering argument.
- **A gap this ordering does not close, left as a known limitation rather
  than fixed:** a budget or rule created *after* its category was
  soft-deleted but before that category is hard-deleted (the category row
  still exists, so the foreign key permits the insert) is not itself old
  enough to be reaped on its own account when the category finally is. The
  category's hard delete still cascades to it, and that budget or rule's
  `sync_shadow`/`sync_conflicts` rows are orphaned exactly the way the
  reversed reap order exists to prevent for the ordinary case. Narrow --
  nothing in the UI creates a budget under a category already marked
  deleted -- and not something this slice built machinery to close. §11.

---

## 8. The backend (M4b)

This app has never made a network call. Everything below is the largest change in
the project's history and the part with the least design interest per unit of
risk, which is the argument in §2 for building it second.

### 8.1 What the data actually is

Worth writing plainly before choosing where to put it. The synced payload is:
every transaction's amount, date, merchant description, account, free-text notes,
and category; every category name; every budget limit per category per month;
every auto-categorisation rule, which are literally strings of merchant names the
user cares about. For most people that is a more complete picture of their life
than their photo library — where they eat, when they travel, what they pay for
healthcare, who they send money to, how much they earn and when it stopped.

That is the thing being uploaded. Every option below gets judged against it.

### 8.2 Options

**A — Hosted Postgres with row-level security** (Supabase, Neon, or self-hosted).

- *Auth:* a real account system — email/password or OAuth — plus token refresh,
  password reset, and account deletion. Against PostgREST it is roughly one
  dependency (an HTTP client; `kotlinx-serialization-json` is already here), since
  the vendor SDKs are large multi-artifact trees.
- *Semantics:* the best of the three. Server-assigned monotonic revisions,
  real transactions, atomic compare-and-swap, server timestamps, and the ability
  to reject a stale write rather than hope. Everything in §4 and §7 gets easier.
- *CI:* the worst of the three. Either a live project plus credentials in GitHub
  secrets (which means CI can no longer be run by a fork, and a leak is a leak of
  a real database), or Testcontainers-with-Docker bolted onto a job that is
  already 45 minutes.
- *Privacy:* the user's entire financial history sits in a database **I operate
  and can read**. RLS stops one user reading another; it does nothing about the
  service-role key, which by construction reads everything. For a portfolio
  project this is also a liability question, not just an ethical one: the moment a
  real person uses it, I am holding their financial records.
- *Cost:* free tier, then money, and monitoring, and a backup policy.

**B — The user's own cloud storage: Google Drive `appDataFolder`.**

- *Auth:* Google Identity authorization for the `drive.appdata` scope. Two
  dependencies (`play-services-auth` for the authorization flow, and an HTTP
  client for the Drive REST API — the official Drive Java client pulls a large
  tree and is not worth it for four endpoints). No account system: the user's
  Google account is the account.
- *Semantics:* the worst of the three. Dumb file storage — list, upload,
  download, delete. No transactions, no compare-and-swap, eventually-consistent
  listing. Which is exactly why it forces every piece of intelligence onto the
  device, where it is testable (§10) — the design in §3–§7 is *shaped* by this
  choice and is better for it.
- *CI:* trivially the best. There is nothing for CI to talk to. The transport is
  an interface; CI exercises the fake.
- *Privacy:* the data goes to the user's own Drive, in a folder that is invisible
  in the Drive UI and readable only by this app and the account owner. **I never
  hold it and cannot read it.** Google can, unless the payload is encrypted before
  upload (§8.4). If the user stops using the app, their data goes away with their
  account, and there is no server for me to have to delete it from.
- *Cost:* zero, forever.

**C — No backend at all.** Device-to-device over the local network, or an
exported sync bundle the user moves by hand. Zero auth, zero exposure, and it is
not sync — it is a manual transfer with extra steps. Worth naming only because
the file-bundle version is a two-day fallback if M4b's auth work goes badly, and
it would exercise the same engine.

**D — Firebase Firestore.** Rejected on grounds that are specific rather than
generic: Firestore ships its own offline cache and its own conflict handling, so
adopting it would mean either running two offline stores side by side or
replacing Room. Either way, **the problem this milestone exists to solve gets
outsourced to a library** — and the README's claim about it stops being true.
Reasonable choice for a different app; disqualifying for this one.

### 8.3 Recommendation: B, as an append-only op log

**Recommendation: option B.** The privacy answer is the one a finance app should
be able to give, it costs nothing to run, and its weak semantics push the
interesting work onto the device where the tests are.

Shape:

- Each device appends immutable batch files: `ops/<deviceId>/<seq>.json`.
- A pull is: list files, take everything after `lastSyncCursor` (which becomes a
  per-device sequence map, not a single string — see below), download, apply
  through §5.
- A push is: write one file. Files are never modified, so there is no lost-update
  race on the ordinary path.
- **Compaction:** periodically a device writes `snapshot/<n>.json` containing the
  merged current state, then deletes op files strictly older than the snapshot's
  watermark. Old devices that missed the window take §7's full-reconciliation
  path against the snapshot.

`UserPreferencesDataSource.lastSyncCursor` is a single `String?` today. It has to
become a per-device map (`{deviceId: seq}`), because "everything after cursor X"
is not expressible as one number across independently-appending writers. That is
a DataStore key change, not a schema change.

**The biggest unfixed risk in M4b, named rather than buried:** compaction has no
compare-and-swap. Two devices compacting at the same time can each write a
snapshot and each delete op files the other's snapshot didn't include, and ops can
be lost. Mitigations that reduce but do not eliminate it: only compact when the
op-file count crosses a threshold; never delete an op file younger than a fixed
grace period; write the snapshot fully before deleting anything; and have each
device verify after compaction that its own last-pushed sequence still resolves. A
correct solution needs a lock the storage doesn't offer. If this turns out to bite
in practice, it is the strongest single argument for option A, and the engine
from M4a moves across unchanged — which is the point of the split.

**M4b design pass, 2026-09-01 — quantifying the risk named above, since
building the transport means this needs an answer rather than a hedge.**

*What Drive actually offers, checked against the API v3 reference rather than
assumed.* Files have an `id`, an `md5Checksum`, and a revision history, but
`files.update` exposes no conditional-write precondition — nothing analogous
to S3's `If-Match` or Cloud Storage's `ifGenerationMatch`. There is no way to
ask Drive "write this content only if the file is still at the revision I last
read." The revisions resource lets a caller inspect history *after* the fact;
it does not let one guard a write *before* it happens. And listing
(`files.list` against `appDataFolder`) carries no documented consistency
SLA — Google states no bound on how quickly a create or delete by one session
is reflected in a `list` call from another. The honest answer to "does Drive
give us enough" is no, on both axes this needed: no compare-and-swap, and no
guaranteed-fresh listing to fall back to instead.

*The failure mode, worked through rather than asserted.* Naively, "delete only
the exact file ids I just folded into my own snapshot" — not a re-query by
watermark — removes the most obvious version of the race for free: a device
that lists, builds `snapshot/<n>.json`, and deletes precisely the ids it read
never touches a file pushed after its listing, because that file has a
different id and was never in its delete set. The race that survives needs two
devices compacting independently, close enough together that their listings
diverge — device A's listing misses an op file device B's includes, or the
reverse, plausible under "no consistency SLA," not exotic. A produces
`snapshot/5.json` with a smaller horizon; B, moments later, produces
`snapshot/6.json` with a larger horizon that supersets A's. B's delete step is
correct *by B's own accounting* — every file it deletes is one it folded into
`snapshot/6.json`. The loss shows up one level away: a reader calling
`snapshot()` has no principled way to know `snapshot/6.json` supersedes
`snapshot/5.json` rather than being a sibling — `n` is assigned locally by
whichever device compacts, not by anything that enforces a total order, so two
devices computing it from their own stale listings can produce values in
either order or even collide. A reader that picks the wrong one is not merely
looking at stale data (recoverable next cycle): if it picks `snapshot/5.json`
and the op file that only `snapshot/6.json`'s horizon accounts for has already
been deleted by B, that op is gone — not in the chosen snapshot's content, and
its raw file no longer exists. That is the concrete shape of "ops can be
lost," not a gesture at concurrency in general.

*Does it bite, in this app specifically.* The window needs several unlikely
things stacked: two of a 2–3 device household compacting within Drive's
(unbounded, undocumented) listing-propagation delay of each other, with their
listings actually diverging in that window, and a reader subsequently
preferring the smaller-horizon snapshot. Compaction is opportunistic and
threshold-triggered, not scheduled, so two devices crossing the threshold at
the same moment needs a specific prior history — both offline together, then
both catching up at once, which is exactly the "tablet in a drawer for a
season" scenario §7 already sizes the tombstone horizon against. Not exotic;
not the common case either. **It can bite, rarely, and only in that specific
window** — and given that, this design does not ship it as a
mitigated-but-still-live risk the way the paragraph above originally left it.
It closes the window structurally instead.

**Decision: only one device ever compacts.** A single, long-lived
`compaction/owner.json` file (`{deviceId, claimedAt}`) is the elected
compactor. On first sync, a device that finds no owner file writes one
claiming itself, waits a short window (30 seconds — comfortably longer than
any plausible listing-propagation delay, cheap because election happens once
per install rather than every cycle), re-reads the owner file, and proceeds
only if it still names itself; if it now names a different device, it defers
permanently and never compacts. Every other device checks the owner file
before compacting and, if it isn't the named device, never attempts to. This
does not eliminate the race — claim-then-verify is the same unguaranteed
read-after-write this whole analysis is about, so two devices can still both
believe they won a simultaneous first claim — but it collapses "can happen on
every compaction, indefinitely, for the life of the app" into "can happen
once, at first-sync election, and never again after." A false double-election
at that single moment is also lower stakes than the general case: there is no
prior snapshot for the two claimants to disagree about yet, so the worst case
is two owner files racing (resolved by both sides independently computing the
same tiebreak — lexicographically-lower `deviceId` wins, the same
"deterministic so it converges" shape §5.5's tiebreak already uses) rather
than two *snapshots* with divergent horizons. The cost is a single point of
staleness if the elected device is lost or stops running — compaction simply
stops, which is safe by the same argument D8 already makes about the tombstone
horizon: under-compacting wastes storage, it does not lose data — and needs a
manual takeover: a Settings affordance, "no device has compacted in 90+ days,
make this the compactor," gated on the same horizon constant §7 already names.
D13.

### 8.4 Encryption at rest — the app's job or the provider's?

Under option B, Drive encrypts at rest and holds the keys. So without app-layer
encryption the honest sentence is: *your financial history is stored in your
Google Drive, and Google can read it.* Not a scandal — the same is true of the
bank statement PDFs already in the user's Gmail — but it should be stated in the
app rather than left for someone to work out.

**The statement, written out, because a finance app owes one.** This holds
whether or not app-layer encryption ships, and it belongs in Settings next to
the sync toggle, in words a non-engineer reads once and understands:

- **What the data is.** Every transaction — amount, date, description, merchant,
  category, notes, account id — plus the tombstones of the deleted ones, the
  budgets, and the categorisation rules. Together that is a more complete record
  of where someone lives, what they are treated for, who they pay and when their
  salary lands than any other file on the phone.
- **Where it goes.** Into the `appDataFolder` of the user's *own* Google Drive.
  Not a server I run: I hold no copy, and there is no account of mine to
  compromise. That folder is hidden from the Drive UI and reachable only by an
  app holding the `drive.appdata` scope for that user.
- **Who can read it.** Not *"any app the user authorises for `drive.appdata`"*
  — that was wrong when this section first said so, and it's worth fixing in
  place rather than quietly. `appDataFolder` is scoped per OAuth client, not
  per scope grant: a second app the user has separately authorised for
  `drive.appdata` gets its own empty folder through that alias, not this
  app's — Google's own Drive API guide states each app's app-data section is
  invisible to every other app, not only to the user. It also isn't in the
  Drive UI and isn't included in a Google Takeout export; the only ordinary
  way to reach it is through Bahi itself. So the honest list, without
  app-layer encryption, is shorter than this document first claimed but not
  empty: **Google**, who encrypt at rest but hold the keys, and can decrypt if
  compelled by legal process, by an internal support or abuse investigation,
  or in the event of a defect in their own systems; and **anyone who takes
  over the user's Google account outright** — a phished password, a stolen
  session, a SIM-swapped recovery flow — since account control is sufficient
  to complete the same OAuth consent Bahi itself uses and then read the
  folder the same way a legitimate sign-in would. With app-layer encryption,
  both of those get ciphertext instead of a ledger; only someone holding the
  passphrase, which never leaves the device, gets the plaintext.
- **Whose job encryption is.** Under (b) it is the provider's, and the honest
  form of that answer is *acceptable because it is the user's own account, not
  mine* — which is a real answer, and defensible, but only when the app says it
  rather than implying it by silence. Under (a) it is the app's.
- **What is true today.** Nothing leaves the device, and nothing will until M4b
  ships. M4a builds the merge engine against a fake transport (§10.1): no
  network code, no account, no upload. Everything above is a commitment about
  the milestone after this one, not a description of the current build.

Adding it is cheaper than it sounds and needs **no new dependency**: AES-256-GCM
with a key derived from a user passphrase via PBKDF2, both from `javax.crypto`.
The payload envelope carries version, salt and nonce; ciphertext is the op batch.

**Corrected while building 9c: no salt in the envelope.** That sentence
predates the AndroidKeyStore-cached-key refinement two paragraphs below --
a salt travelling on every envelope would only matter if every encryption
re-derived the key from the passphrase, which is exactly what caching the
derived key exists to avoid. The salt PBKDF2 actually uses lives once, in
the persisted key material (`SyncEncryptionKeyStore`), not on the wire. What
every envelope still needs, and does carry, is a fresh nonce -- GCM's
security argument requires one per encryption under a given key, and the key
is reused across many batches. `OpBatchCipher`'s doc has the full format.

The costs are real:

- The user must type the passphrase on every device. That is the same flow every
  password manager uses, so it is familiar, but it is a step.
- **A lost passphrase is unrecoverable**, by construction. There is no reset. For
  a finance app whose local data is unaffected — the passphrase protects the
  *synced copy*, not the device — that is survivable, and it must be said in that
  exact way in the UI: losing it costs you sync, not your ledger.
- Debugging is harder; the remote becomes opaque blobs.

**The case for (b), argued rather than assumed away.** The corrected list
above is the fair version of it: skip app-layer encryption, keep the
disclosure, and say plainly that Google can read this if compelled and that a
compromised Google account is a compromised ledger. That's a real, shippable
position — real products take it, and this section already called it
"defensible" above it. Three things keep it from being a strawman:

1. **The cost lands immediately, not later.** Every other "pay now, it
   compounds later" call in this repo — `importBatchId`, the sync columns
   landing before sync existed — was free to the user: an extra column
   nobody sees. This one is not. Every device gets a passphrase prompt at
   setup, every additional device gets a second one with no shortcut (§8.4
   below), and a forgotten passphrase permanently forfeits the synced copy.
   The recommendation this section reaches borrows the same "cost compounds,
   decide now" heuristic csv-import-design §11.1 and budgets-design §4.1 used
   — but that heuristic was measuring engineering cost, which does compound
   here exactly the way it did there; it says nothing about the user cost,
   which is paid up front, on every install, encryption or not.
2. **It's the step sync adoption is most likely to die on.** §1's whole case
   for building sync is that a second device is useless without it; a setup
   flow that asks for OAuth consent and then a second, unrecoverable,
   must-be-remembered secret roughly doubles the friction of turning it on
   at all. This document has no data — it's a portfolio app with no users —
   that the disclosure-only version of (b) wouldn't clear that adoption bar
   just as well on its own.
3. **The strongest version of the old justification didn't hold.** "Any app
   with the scope" was the most concrete, most alarming-sounding reason to
   encrypt, and it was wrong (above). What's left — Google under compulsion,
   and account takeover — is real, but it's the same pair of actors every
   cloud-synced app on this phone already exposes the user to, Gmail
   included, per this section's own comparison to bank statement PDFs
   already sitting there. Singling out Bahi for a passphrase Gmail doesn't
   ask for is a defensible choice, not an obviously correct one.

None of that reverses the recommendation, but it changes why it's right.
(1) and (2) are real costs and stay real no matter which option wins; (3)
shrinks the threat model without emptying it — Google-under-compulsion and
account-takeover are exactly the two actors a passphrase that never leaves
the device is good at stopping. A subpoena or a phished password hands an
attacker `appDataFolder` access, not a key, and the ciphertext sitting behind
it is exactly as useless to them as it would have been if the "other apps"
reasoning had been the correct one all along. And the asymmetry the
`importBatchId` comparison misses cuts the other way, too: guessing wrong by
shipping *without* encryption costs more than guessing wrong by shipping
*with* it. Adding encryption later means re-encrypting a live corpus,
re-pairing every device, and explaining to whichever users exist by then why
the trust model just changed under them; over-building it now costs one
avoidable setup step for however long the app never has that problem.

**Recommendation: encrypt, in M4b's first slice, not later — (a), for the
corrected reason above rather than the original one.** D9.

**Decided, 2026-09-01 — (a).** M4a moved no data off the device, so D9 was
correctly left open through that milestone (§14's entry said as much). This
design pass is M4b's, and M4b is the milestone that moves data, so the
deferral no longer applies, and the choice above is not a hedge anymore. Two
flows have to exist for "encrypt" to mean something, and the paragraphs above
stop short of them.

*Where the key lives, day to day.* PBKDF2-derived keys are deliberately slow
to compute — that is the point of PBKDF2 — so re-deriving on every sync,
potentially several times an hour once the periodic worker exists (§8.7), is
not something to ask a phone's CPU or the user's patience for repeatedly. The
derived key is cached, not the passphrase: after the user types it once during
setup, the resulting AES-256 key is wrapped with a hardware-backed
`AndroidKeyStore` key (tied to that installation) and the wrapped blob is
stored in `DataStore` — the same "state you keep is per-device by definition"
shape `lastSyncCursor` already has (§1.1's scope table). The raw passphrase is
held in memory only for the derivation and discarded immediately after. This
means the passphrase is typed once per device, at setup, not once per sync.

*The new-device flow.* A second device authenticates to Drive (§8.6) and can
list the op log, but every payload is ciphertext it cannot open until it holds
the same key — and the key cannot be derived without the passphrase, which by
design never travels through Drive or anywhere else off-device. So pairing a
second device is: sign in, then type the same passphrase by hand. There is
deliberately no QR-code-from-the-first-device shortcut in this design, because
that shortcut is itself a channel the passphrase would have to cross, and the
entire point of a passphrase-derived key is that it crosses no channel this
app controls. The cost is one manual step per device, stated plainly as a cost
rather than hidden as a convenience.

*The lost-passphrase flow.* Exactly as bad as it sounds and no worse: the
local ledger on every device that already holds the cached key is completely
unaffected, because the passphrase protects the synced copy, not the data.
What is lost is the ability to pair a *new* device into that same sync group,
or to decrypt the op log from a device that never had the key cached (a
factory-reset phone, a fresh install). The only recovery is deleting the
remote data and starting sync over with a new passphrase — the "the app must
not pretend a lost key is recoverable" case D9 already named. The UI wording
is load-bearing here, and is copied out because it is easy to get subtly wrong
in a way that either scares a user unnecessarily or reassures them falsely:
**"This passphrase protects your synced backup only. Losing it means starting
sync over on a new passphrase — your transactions on this device are safe
either way."** Never "your data will be lost."

### 8.5 What a fresh clone sees, and D12

**D12 — the fresh-clone experience.**

- **Options:** (a) sync UI hidden entirely until `sync.properties` exists — the
  feature isn't there for a reviewer who hasn't set up a Cloud project. (b)
  sync UI visible, disabled, with an explanation and a documented setup path.
  (c) a mocked/demo sync mode that fabricates a second device in-process so a
  reviewer sees the real screens without a Google account at all.
- **Recommendation: (b).** This is a portfolio project (CLAUDE.md): the point
  of M4a's convergence suite and this design is to be *read*, and a reviewer
  who opens Settings and finds nothing where the README's second headline
  feature should be has no way to know sync exists at all short of reading
  source. (a) hides the most-discussed feature in this repo from the person
  most likely to go looking for it. (c) is tempting and specifically wrong for
  this app: a fabricated second device is exactly the "in-memory fake" M4a's
  convergence suite already *is*, and building a second, UI-facing version of
  that same fake risks a reviewer mistaking a demo for evidence — this design
  has been careful, throughout M4a, to keep "the convergence suite proves
  this" and "a reviewer can see this run" as separate claims (§10.1), and a
  demo mode blurs exactly that line.
- **If wrong:** (a) is a low-cost reversal — one conditional around a nav
  entry. (c) is the expensive direction: unwinding a demo a reviewer may have
  already judged real sync by, and re-explaining that what they saw wasn't it.

**What (b) means concretely**, extending the sketch already in place before
this pass:

- `SyncModule` reads an optional, gitignored `sync.properties`. Absent, it
  binds `DisabledSyncTransport` — a `SyncTransport` that throws on
  `push`/`pull` and returns an empty snapshot, so nothing that calls it can
  silently behave as if sync ran. `SyncEngine` is never constructed against it
  in this state at all (§8.7 covers when it *is* constructed); the binding
  exists so the module graph never needs a nullable `SyncTransport?` threaded
  through Hilt.
- The Settings screen's sync row (§8.8 extends this further) shows "Sync — not
  configured," one line of what that means (§8.4's disclosure statement, which
  ships regardless of whether the row is active), and a link to the setup doc
  rather than a dead-looking toggle.
- **The setup doc is the actual deliverable of this decision**, not an
  afterthought: a README section, or a linked `docs/sync-setup.md` if the
  instructions get long enough to clutter the README the way
  `docs/csv-import-design.md` and this file already live outside it, walking a
  reader through creating a GCP project, enabling the Drive API, registering an
  Android OAuth client against their own debug keystore's SHA-1
  (`keytool -list -v -keystore ~/.android/debug.keystore`, the exact command,
  not a description of one), and writing the resulting client id into
  `sync.properties`. Fifteen minutes, no payment method, no review process —
  `drive.appdata` access for a debug-signed app in testing mode does not
  require Google's OAuth verification review, which is otherwise the
  multi-week blocker this flow would hit if it requested a broader scope
  (§8.6).
- **Nothing about this can silently rot**, which the doc's own
  screenshot-staleness lesson (CLAUDE.md; §11's `settings-conflicts.png`
  finding) argues has to be said explicitly rather than assumed: the "not
  configured" copy is exercised by `SettingsScreenTest` against
  `DisabledSyncTransport` directly, not a fake standing in for it, so a change
  to `SyncModule`'s gating logic that breaks the fallback fails a test rather
  than only being caught by someone who happens to run a clean checkout.
- **CI never needs any of this.** `./gradlew unitTests` and the instrumented
  job exercise `InMemoryTransport`, same as M4a — the fresh-clone path
  (`DisabledSyncTransport`) is exercised by unit/instrumented tests directly,
  and the *real* Drive path is exercised by nothing CI runs, which §10.5
  states plainly rather than folding into this section's optimism about what
  is covered.

### 8.6 OAuth: library, scopes, refresh, revocation

**Library.** Not the deprecated `GoogleSignInClient` — Google's own guidance
points away from it. The scope this app needs, `drive.appdata`, is an
*authorization* scope, not an identity claim, so the fit is the
**Authorization API** inside Play Services (`Identity.getAuthorizationClient()`),
which is what replaced `GoogleSignInClient`'s scope-consent path. That is one
new dependency, `com.google.android.gms:play-services-auth` — already the one
named in §8.2's option-B sketch. Ask-before-adding per CLAUDE.md; named here
as the recommendation for that conversation rather than added.

**Corrected while building 9d: no `androidx.credentials` dependency.** That
artifact backports the newer Credential Manager *sign-in* flow to older API
levels; it has nothing to do with the Authorization API's *scope-grant* flow
this section actually specifies, and the hedge above was wrong to imply it
might be needed alongside it. Checked against Google's own current
documentation, not assumed. `play-services-auth` alone is the whole of the
new dependency surface.

The alternative considered and rejected: a provider-agnostic OAuth flow via
AppAuth (Custom Tabs + PKCE, no Play Services dependency at all). It would
work on a device without Play Services, which this app has never had a reason
to care about — Drive itself is a Google product, so a device that can't run
Play Services can't productively use this feature regardless of which library
requests the token. Paying for provider-independence with a heavier,
hand-rolled OAuth flow buys nothing here.

**Scope.** `https://www.googleapis.com/auth/drive.appdata`, and nothing
broader, ever. Not `drive.file`, which would let the app see files the user
picked through a system chooser (irrelevant — this app never wants the user to
pick a file), and not `drive`, which reads the user's entire Drive. `appdata`
is also what keeps this app out of Google's OAuth verification review process
(§8.5) — a broader scope would require it, and would be a much harder thing to
justify for a project whose entire privacy pitch (§8.1, §8.4) is "I cannot
read your data."

**Refresh.** The Authorization API is responsible for minting and refreshing
access tokens once consent is granted; this app does not store a refresh
token itself. What it stores is one boolean-shaped fact — "this device has
been granted `drive.appdata` access" — in `DataStore`, next to
`lastSyncCursor`. Before each sync cycle (§8.7), the engine asks the
authorization client for a current access token; a silent refresh happens
underneath when the cached one has expired, with no user interaction, as long
as consent hasn't been revoked.

**Revocation.** The one case that is not silent. A user who revokes access
from their Google Account's "Third-party apps & services" page invalidates the
refresh token from Google's side; the next token request this app makes fails
with an authorization error rather than returning a fresh access token. That
failure has to be classified, not treated as an ordinary network error:
retrying it on the WorkManager backoff schedule (§8.7) would retry forever for
a request that can never succeed until the user acts. `SyncEngine`'s run-state
(`SyncStatus`, `:core:model` — currently `Idle`/`Running`/`Failed`, unused
today per §13 slice 8's note that nothing calls it yet) gains
`NeedsReauthorization`. The Settings row surfaces it distinctly from a generic
sync failure — "Sync access was removed — reconnect," with a button that
re-runs the consent flow — and the periodic worker treats it as terminal for
that cycle rather than something to back off and retry, since backing off
implies the next attempt might succeed on its own, which this one will not.

### 8.7 The periodic worker

WorkManager has sat in the version catalog unused since M0 (§9's note); this
is what finally calls it, and it is what gives `SyncEngine` its first real
caller anywhere in the app (§8.8, §11's flagged gap).

**Cadence.** Android's `PeriodicWorkRequest` floor is 15 minutes, but this app
has no reason to sync that often — §1 states plainly that real-time
propagation is out of scope, and the data volume is a personal ledger's worth
of edits, not a stream. **Recommendation: a periodic tick every 4 hours** as
the background safety net, `NetworkType.CONNECTED` (not `UNMETERED` — the
payload is small JSON, not media, and gating a finance app's sync on Wi-Fi
only would mean days of drift for someone mostly on cellular) and
`setRequiresBatteryNotLow(true)`. Alongside it, an **expedited one-time
request fired when the app moves to the foreground** and when the user opens
Settings — the periodic tick is the guarantee against staleness for a phone
left alone, but a user who just edited on their phone should not wait up to 4
hours to see it on their tablet, and foreground sync is cheap because the user
is already spending battery having the screen on.

**Failure handling.** WorkManager's own `BackoffPolicy.EXPONENTIAL` covers
transient failures within one execution — a Drive 5xx, a dropped connection —
up to a capped number of attempts before that execution gives up for the
current tick; the next periodic tick or the next foreground event is the
natural retry, so nothing needs a second, hand-rolled retry loop layered on
top of WorkManager's. Two failure classes need to be told apart, both by the
worker and by what it reports:

- **Transient** (network, Drive rate limiting, a timeout) → `Result.retry()`,
  backoff applies, `SyncStatus.Failed` with the underlying cause, no user
  action implied.
- **Terminal for now** (revoked authorization, §8.6; Drive quota genuinely
  exhausted rather than rate-limited — checked separately, since Drive's 429
  for "too many requests" and 403 for "storage quota exceeded" mean different
  things and only the second is something retrying can never fix) →
  `Result.failure()`, no further attempts this tick, `NeedsReauthorization` or
  an equivalent quota-exhausted state surfaced in Settings rather than
  silently retried into the next tick.

**What "fails repeatedly" looks like to the user.** A worker that silently
fails forever in the background is exactly the failure mode this section
exists to avoid — a finance app whose sync has been broken for three weeks and
never said so is worse than one with no sync at all, because the user believes
their second device is current when it is not. The unacknowledged-conflict
count on the Settings row (§5.6, already built) is the wrong signal for this —
it can be zero because nothing has conflicted, not because sync is healthy.
This needs its own signal: a last-successful-sync timestamp, always shown on
the Settings sync row once sync has run at least once, going from a quiet fact
("Last synced 4 minutes ago") to a visible warning past some threshold ("Last
synced 6 days ago — check your connection") without needing a notification —
§5.6 already declined notification infrastructure for a less urgent case, and
that argument doesn't get waived just because this is about plumbing rather
than a conflict.

### 8.8 The conflict screen's first real render

§11 names the gap directly: every row that has ever populated
`SettingsScreen`'s populated state came from `FakeSyncConflictRepository`,
because `SyncEngine` has had no caller anywhere in the app through all of
M4a. §8.7's periodic worker and foreground trigger are what change that — the
first time two real devices, both running a build with `sync.properties`
configured, edit the same field while both offline and then both come online,
`RoomSyncApplier`'s resolver-driven path writes a `sync_conflicts` row nothing
fabricated, and `SettingsScreen` renders it.

Two things follow, and neither is optional once it's reachable rather than
hypothetical:

**The `category_id`-renders-as-uuid gap (§11, slice 8's known simplification)
stops being a corner case.** It was accepted in M4a because the only
conflicts anyone could produce were seeded, and seeded conflicts used the
readable system-category slugs. A real two-device conflict has no reason to
prefer a seeded category over a user-created one, and a user-created
category's id is `UUID.randomUUID()` the same as everything else in this app.
Shipping M4b without closing this means the first thing a real user sees on
their first real conflict is `Kept: 7f3a9c21-...`, which is the exact
"list nobody reads" failure §5.6 was written to prevent, arrived at through a
different door. **This is now a required M4b slice, not a nice-to-have**:
`SettingsViewModel` joins `category_id` against `observeCategories()` the same
way `RuleListItem`/`BudgetRow` already resolve a category for display (§11's
own description of the fix), falling back to the raw id only if the category
itself is gone.

**The screenshot has to be re-earned, not re-labelled.** `settings-conflicts.png`
was captured against a hand-seeded database (slice 8's note) — an honest fake,
but still a fake. CLAUDE.md's screenshot rule is about drift, not provenance,
but the same discipline applies here for a stronger reason: a screenshot in a
portfolio repo captioned as evidence of a working feature should be evidence,
not a mockup with real chrome. The M4b slice that wires the periodic worker up
should run two real devices (or two emulators with real Drive access, §10.5)
to genuine convergence, produce a genuine conflict, and recapture
`settings-conflicts.png` from that — at which point it is also the first real
check on everything §5.6 flagged as untested against real data: whether
`chosenValue`/`discardedValue` render for a shape the resolver actually emits
and the fakes never happened to construct, whether a real `reason` string
wraps the way the fakes' did, whether a `table_name` the tests never fed it
does anything unexpected. If any of those needed fixing, this is where it
would show up, and it should be looked for deliberately rather than left to be
found by whoever opens the screen next.

---

## 9. Data layer changes

New tables, `MIGRATION_5_6`, all `CREATE TABLE` and purely additive:

```
sync_shadow
  table_name       TEXT NOT NULL
  row_id           TEXT NOT NULL
  remote_revision  INTEGER NOT NULL
  payload          TEXT             -- NULL = deleted as of remote_revision
  PRIMARY KEY(table_name, row_id)

sync_conflicts
  id               TEXT PK
  table_name       TEXT NOT NULL
  row_id           TEXT NOT NULL
  field            TEXT NOT NULL
  resolved_at      INTEGER NOT NULL
  chosen_value     TEXT NOT NULL
  discarded_value  TEXT NOT NULL
  reason           TEXT NOT NULL
  acknowledged_at  INTEGER
  INDEX(table_name, row_id, field)
  INDEX(acknowledged_at)
```

`categories` gained `local_revision INTEGER NOT NULL DEFAULT 1`,
`remote_revision INTEGER`, `pending_operation TEXT`, `deleted_at INTEGER` — the
same four every other table already has — in `MIGRATION_3_4`, in slice 1.

**Three corrections to the sketch above, from building it.**

`sync_shadow.payload` is **nullable**, not `NOT NULL`. A null payload is a base
that says the row was deleted at that revision, which is a different fact from
having no base at all, and the absence of a row is the only way to say the
second. With a `NOT NULL` payload a device that pulled a deletion and then
revived the row locally could not tell its revival from a row the remote never
deleted (§4.1).

`sync_conflicts` gets `INDEX(table_name, row_id, field)` rather than
`INDEX(row_id)`. Both the supersede check and the per-row read start from
(table_name, row_id), so one composite index serves both; a lone index on
`row_id` would serve neither, since `row_id` is not a prefix of the composite and
no query asks for a row id without knowing which table it belongs to.

The tables land in `MIGRATION_5_6`, not `MIGRATION_3_4`. Slices 1 and 2 spent
those numbers on the category columns and the identity rewrite.

Neither new table carries sync bookkeeping of its own: they are local records
*about* sync, and syncing them would be circular. That is the one place this
codebase's "every table gets the four columns" habit does not apply, and it is
worth a comment on each `@Entity` saying so, since the pattern is otherwise
uniform enough that their absence reads as an oversight.

**Module placement.** `:core:sync` already depends on `:core:model`,
`:core:common`, `:core:data` and `:core:datastore`, and already has WorkManager,
Hilt-Work and kotlinx-serialization wired up. No new module and no new edge:

- `:core:model` — `SyncOp`, `OpBatch`, `SyncTable`, and a reshaped
  `FieldResolution`. `SyncMetadata` gains nothing; it is already right. The
  serialization plugin has been on this module since M0 with nothing using it;
  this is what it was wired for. `kotlinx-serialization-json` becomes `api`
  rather than `implementation`, because `JsonObject` is in `SyncOp`'s signature.
- `:core:database` — the two entities, their DAOs, `MIGRATION_5_6`, the schema
  JSON, `MigrationTest` cases, the guarded `markSynced`, and the category
  soft-delete changes.
- `:core:data` — shadow read/write, the reconciliation queries, and the mapping
  between entities and field maps (`SyncPayloads.kt`, `internal` because it
  takes entities). Only entity —> field map lives there: the resolver reads
  base *values* out of a payload and never needs an entity back, so one
  direction is the whole answer for the shadow. Field map —> entity is what
  *apply* does, it has to cope with a partial map, and it belongs to the engine.
  **The resolver's field policies live here or in `:core:sync`, not in a
  feature** — nothing in `:feature:*` may see an entity (rule 3).
- `:core:sync` — `ConflictResolver` and its policies, `SyncEngine`,
  `SyncTransport` plus the fake, the `CoroutineWorker`, and (M4b) the Drive
  transport.
- `:feature:settings` — the sync screen and the conflict list. This is the
  milestone that gives that module its first real screen; it is a stub
  composable today.

**Corrected while building slice 5c: "field map -> entity ... belongs to the
engine" was wrong about which engine.** `:core:sync` depends on `:core:data`
(`implementation`, not `api`), so it never has `TransactionEntity` or
`BahiDatabase` on its classpath at all — rule 3 isn't a discipline `:core:sync`
chooses to keep here, the module graph makes it physically incapable of doing
otherwise. So `transactionFromFieldMap` and its three siblings live in
`SyncPayloads.kt`, `:core:data`, next to `toFieldMap` — the "one direction is
the whole answer" sentence two paragraphs up turned out to describe the
shadow specifically, not the general rule the next sentence tried to draw
from it.

That in turn moves more than the field-map reconstruction. §6.2's requirement
— a pulled batch applies in one Room transaction — means whatever reads a
row's current state, decides the merge, and writes the result has to do all
three inside that one transaction, or a concurrent local edit landing between
a stale read and a later write would be silently overwritten. Deciding the
merge is `ConflictResolver`'s job and has to stay in `:core:sync` (that is the
whole point of the module split above), but the transaction itself can only be
opened where `BahiDatabase` is visible, which is `:core:data`. The two
requirements meet at a narrow interface owned by `:core:data`,
`RemoteMerge` (`SyncApplier.kt`'s neighbour, `RemoteMerge.kt`) — a pure
`(table, local, remote, base) -> outcome` function, structurally identical to
`ConflictResolver.resolve` but not the same type, so `:core:data` never
imports `:core:sync`. `:core:sync`'s `ConflictResolverRemoteMerge` adapts one
to the other and is bound to it by Hilt. The result: `SyncEngine` (`:core:sync`)
does no classifying, merging or writing of its own — it fetches what changed
on each side and hands the raw ops to `SyncApplier.apply` (`:core:data`), which
owns the whole read-merge-write transaction. `SyncApplier` is the piece the
original sentence meant to describe as "the engine"; it just does not live in
the module named that.

**WorkManager finally earns its keep.** It has sat in the version catalog unused
since M0, and both previous designs explicitly declined to reach for it —
csv-import-design §6 ("unattended/background work" isn't what an import is) and
budgets-design §1.3 (a rule change is a user action). Periodic sync *is*
unattended background work with a network constraint and a retry/backoff policy,
which is the case those two documents were reserving it for. `:core:sync` already
declares the dependency.

---

## 10. Testing

This is the question most likely to decide whether the design is buildable, so
the answer has to be concrete.

### 10.1 Two devices, one process

`SyncEngine` is constructed from a `BahiDatabase`, a `SyncTransport` and a
`deviceId`. Nothing in it is a singleton and nothing reaches for a global. So a
test constructs **two** of them — two separate in-memory Room databases, two
device ids — over **one** `InMemoryTransport`: a mutable list of `OpBatch` behind
a mutex, modelling append-only storage with no ordering guarantee between
devices, which is exactly what §8.3's op log is.

That is a genuine two-device test. It runs as an `androidTest` (it needs real
Room and real SQLite, the same reason `MigrationTest` and `BudgetTotalsTransientTest`
do), on the CI emulator that already runs on every push, with no credentials and
no network. Method names are `lowerCamelCase_withUnderscores` per CLAUDE.md, not
backticked — DEX rejects spaces below API 30 and `minSdk` is 26.

Helper shape: `deviceA.offline { ... }` applies operations through the real
repositories; `syncToQuiescence(deviceA, deviceB)` runs both engines until neither
produces ops; `assertConverged(a, b)` dumps every synced table from both databases
in a canonical order and compares.

### 10.2 Scripted scenarios

One test per row of the conflict matrix, each one: diverge, sync, assert both
databases equal *and* equal to the stated expected result. Convergence alone is
not enough — two devices can converge on the wrong answer.

The list, which is also the acceptance criteria for §5:

1. Disjoint edits to different fields of one row — both survive, no conflict
   recorded.
2. Both edit the amount — deterministic winner, one `sync_conflicts` row with the
   loser in `discarded_value`.
3. A edits, B unchanged — fast-forward, **no** conflict recorded.
4. A deletes, B edits concurrently — the edit wins, the row lives (§5.3).
5. A edits, syncs; B pulls, then deletes — the delete wins (causally after).
6. A locks a category by hand; B's rule engine sets a different one concurrently
   — A's survives, and it survives *because of the resolver*, with B's three
   local guards having correctly permitted the rule to fire (§5.4).
7. Both lock, different categories — tiebreak, recorded.
8. Both create a budget for the same category and month — one row, not two
   (§3.2).
9. The same statement imported on both devices — 400 rows, not 800 (§3.1).
10. That, plus a genuinely duplicated coffee in one of them — the duplicate
    survives (the csv-import-design §4 case, across devices).
11. A imports a batch, syncs; B edits three rows; A undoes the batch — 397 gone,
    3 edited rows alive (§6.1).
12. Concurrent notes edits, each of the three §5.5 branches.
13. A deletes a category; B has transactions in it — the transactions survive,
    show as uncategorised, and undeleting the category restores them (§1.2).
14. Concurrent rule reordering — both devices agree on the final `(priority, id)`
    order, whatever it is (§6.5).

### 10.3 The property test

Scripted scenarios test the cases someone thought of. The interleaving that
breaks a merge engine is usually not one of them.

A generator produces random operation sequences from a small alphabet — create,
edit-a-field, delete, undelete, lock category, import a batch, run rules, edit
budget, reorder rules — applies them to A and B with sync points inserted at
random positions, syncs to quiescence, and asserts `dump(A) == dump(B)`.
Deterministic per seed; the seed is printed on failure so any failure is
reproducible by re-running one number.

Being specific about run counts, because CLAUDE.md is specific about this after
`BudgetTotalsTransientTest`: a **fixed set of 50 seeds runs in CI on every push**
(bounded, deterministic, roughly a minute of emulator time at ~40 ops per seed),
and a **nightly job runs 1,000 random seeds** and files an issue with the seed on
failure. The distinction that matters is that this is an assertion of a *property*
that must hold for every interleaving, not a measurement of which way a race
happened to fall — so a failure at seed 837 is a real bug, not a sample.

What it cannot do: explore operations that aren't in the alphabet. Every time the
app gains a new kind of write, the alphabet has to gain it too, and that is a
convention rather than a mechanism — the one place in this design where "someone
has to remember" is the honest answer.

### 10.4 The rest

- **Field-coverage** (JVM unit test, §5.4): every property of every synced entity
  appears in the resolver's policy map. A new column with no policy fails the
  build.
- **Idempotence:** applying the same `OpBatch` twice changes nothing. Dumb
  storage gives at-least-once delivery, not exactly-once.
- **Push interrupted:** the transport throws mid-push; assert nothing was marked
  synced and that a retry converges. This is what the guarded `markSynced` in
  §4.3 exists for, and it needs a test that fails without the guard.
- **Over the horizon:** a device whose cursor predates the tombstone horizon takes
  the reconciliation path and converges (§7).
- **Missing shadow:** clear `sync_shadow` on one device and sync — every
  differing row is recorded as a conflict rather than silently overwritten (§4.1).
- **`MigrationTest`:** `migrate3To4_addsSyncTablesAndCategoryTombstones`, plus
  the id-rewrite migrations from §3, which are the first migrations in this repo
  that transform data rather than only add structure and deserve tests that seed
  real rows and assert on the values afterwards.
- **Transport contract:** one abstract test class defining what a
  `SyncTransport` must do, run against `InMemoryTransport` in CI and against the
  Drive transport manually (M4b). The real transport gets a specification even
  though CI cannot execute it.

**What CI genuinely does not cover, stated plainly:** real Drive semantics —
listing consistency, token refresh and expiry, quota exhaustion, partial uploads,
and above all concurrent compaction (§8.3). Those get a written manual test plan
run against two physical devices and one real account before M4b ships, and the
plan is part of the M4b slice rather than something assembled afterwards.

### 10.5 Testing the Drive transport, and keeping the manual plan honest

**Does `SyncTransportContractTest` run against Drive?** Yes, and it is the
whole reason §10.4 built it as an abstract class rather than a fake-only test:
`DriveTransportContractTest : SyncTransportContractTest()` overrides
`createTransport()` to construct a real `DriveTransport` against a real,
already-authorized Drive account, and every one of the guarantees
`InMemoryTransportTest` already checks — pull ordering, per-device cursor
independence, an empty horizon before anything has compacted, and the rest —
gets checked against the real backend with zero new test logic. What it
*can't* check by construction: eventual consistency (a JVM test with no sleep
between push and pull won't observe a listing lag that may or may not appear),
the compaction race analysed in §8.3, quota, and token lifecycle — none of
those are property-of-two-calls facts a contract test is shaped to hold.

**Where it lives, and why CI doesn't run it.** `DriveTransportContractTest`
sits in `core/sync/src/driveTest/`, added as a source directory to the `test`
AndroidSourceSet only when `core/sync/drive-test.properties` exists
(gitignored, naming a real OAuth client and a pre-authorized refresh token for
a throwaway Google account — never a CI secret, the same reasoning §8.5
gives for the app build itself, applied to a test). Absent that file, on a
fresh clone and on every CI run, the source isn't merely un-run, it is never
compiled — AGP has no notion of a custom-named test source set, so this adds
the directory to the existing `test` source set and relies on a
`driveTest`/`testDebugUnitTest` filter split to keep it out of a routine
`./gradlew unitTests` even on a machine that does have the file. Run by hand:
`./gradlew :core:sync:driveTest`. Full setup steps: docs/sync-setup.md.

**The manual test plan, and why "manual" doesn't mean "aspirational."** A
checklist that exists once and is never re-run is worse than no checklist — it
is evidence of care that isn't actually evidence of anything current, the same
failure CLAUDE.md's screenshot-staleness rule was written to name for a
different artifact. The mitigation is the same shape: the plan is a table, not
prose, and every row carries a **last-verified date and the commit it was
verified against**, checked in at `docs/sync-manual-test-plan.md` (linked from
here rather than duplicated, since it is a living checklist and this document
is a design record). Empty because none of this is built yet — filled in as
M4b's slices land, the same way this document's own slice list (§13) only
marks a row "done" once it is checked, not once it is planned. **The rule
that keeps it honest going forward:** any PR touching `:core:sync`'s
Drive-facing code re-runs every row whose "against" commit is more than one
M4b slice old and updates the date, and a stale-plan check (every date older
than 90 days — the same horizon number the rest of this design already
uses, for the same "longer than any device is plausibly offline" reasoning,
not because the two clocks need to share a value) is a line item in the M4b
release checklist, not a job CI enforces, because CI is precisely what cannot
exercise these rows.

---

## 11. What this design gets wrong

Collected rather than scattered, because the previous two documents' most useful
sections were the ones that did this.

- **The property test's alphabet is a convention.** §10.3. It is the only
  guard-against-forgetting in the whole design that isn't mechanical.
- **The wall-clock tiebreak is arbitrary and can be wrong.** §5.5. It is bounded
  to "which of two concurrent edits to the same field won," it is deterministic
  and therefore convergent, and every use of it is recorded — but it is still a
  coin flip dressed as a rule.
- **Notes concatenation compounds.** Each conflict's output becomes the next
  conflict's base, so a repeatedly-conflicting note grows. Nothing bounds it. The
  alternative loses text, which is worse.
- **Step 2 of the notes rule undoes a deliberate shortening.** §5.5.
- **Content-derived ids inherit the content hash's fragility.** §3.1. A
  description rendered differently by two exports produces different ids and
  degrades to duplicates.
- **An import undone before its first sync can swallow the same import on
  another device.** §6.1. Rare, real, mitigated by surfacing it rather than by
  preventing it.
- **Rule reordering converges on an order neither user chose.** §6.5.
- **Compaction has no compare-and-swap and can lose ops.** §8.3. This is the
  largest unfixed risk in M4b and the strongest argument for option A.
- **A lost shadow degrades every merge to two-way.** §4.1, mitigated by making
  it loud.
- **Resolver symmetry is untested by construction, and field policies carry no
  version.** §4.1's named assumption. Convergence holds under version skew
  between two policy tables, but "converges" and "resolves the way the user
  expected" can silently separate, and nothing on the wire would detect it.
- **Nothing here handles a second currency or a second account**, because
  nothing in the app does yet. Sync is not the place to introduce either, but it
  is the place where getting them wrong later becomes expensive, because the
  wrong assumption is baked into ids.
- **A budget or rule created under an already-soft-deleted category can be
  orphaned by the tombstone horizon.** §7. `TombstoneReaper` reaps children
  before `CATEGORIES` specifically so a category's `ON DELETE CASCADE` never
  outruns its own budgets and rules being reaped on their own account -- but
  that only holds because `softDeleteUserCategory` tombstones a category and
  its budgets/rules at the same instant. A budget or rule inserted *after*
  that (the category row still exists, so the foreign key permits it) is
  younger, is not reaped on its own account when the category finally is,
  and still gets swept up by the cascade -- leaving its `sync_shadow`/
  `sync_conflicts` rows pointing at nothing. Narrow (nothing in the UI does
  this) and not closed; the ordering argument that closes the ordinary case
  does not extend to it.
- **A `category_id` conflict renders as an unusable id for a user-created
  category.** §13 slice 8. The Settings screen shows `ConflictValue.Text`
  verbatim -- fine for the seeded system categories (`"food"`,
  `"entertainment"`), whose ids are readable slugs, but every other
  user-created entity in this app (a transaction, a rule, a budget) gets its
  id from `UUID.randomUUID()`, and there is no reason a category-creation
  screen would do otherwise once one exists. The row would read `Kept:
  7f3a9c21-...`, which tells the user nothing. `docs/screenshots/settings-conflicts.png`
  is a flattering case precisely because it only exercises a seeded
  category -- it is not evidence this is fine in general. Closing it needs a
  join against `categories` (by id, filtered `deleted_at IS NULL` else
  fall back to the id itself, the same way `RuleListItem`/`BudgetRow`
  already resolve a category for display) at the point `SettingsViewModel`
  builds `ConflictListItem`, not attempted in slice 8.
- **The Settings conflict screen's populated state has no live data path.**
  §5.6. `sync_conflicts` is written only by `SyncApplier`'s resolver-driven
  path, and nothing calls `SyncEngine` until M4b's transport exists — so every
  row that has ever populated that screen came from a fake. The release build
  can't be hand-seeded either (debug-signed, but not `isDebuggable`). M4b
  inherits this directly: the first conflict a real two-device sync produces
  is also this screen's first render against non-fake data.
- **Single-elected-compactor narrows the compaction race to one moment and does
  not close it.** §8.3, D13. Two devices can still both believe they won the
  first-compactor election if their claim-then-verify listings diverge in that
  exact window — rarer and lower-stakes than the general race, since no prior
  snapshot exists yet to disagree about, but not impossible, and nothing
  detects it after the fact beyond the manual plan's own row for it.
- **The manual test plan is only as honest as the discipline that re-runs
  it.** §10.5. Nothing mechanical enforces the re-verification rule the way
  `checkModuleBoundaries` enforces the module graph; it is a checklist item in
  a release process, the same category of "someone has to remember" §10.3
  already names for the property test's alphabet.
- **A lost passphrase is permanent by design, and the UI has exactly one
  chance to say that correctly.** §8.4, D9. The wording matters more than
  usual here because the two ways to get it wrong — understating it (a user
  who thinks it's recoverable and later finds out otherwise) and overstating
  it (a user who thinks their local ledger is at risk and abandons sync, or
  the app, out of caution they didn't need) — are both worse than the accurate
  middle this document commits its copy to.
- **Drive's listing consistency has no documented SLA, and the compaction
  argument leans on "the window is short" without a number to bound it.**
  §8.3. That is a real gap, not a rounding error — everything from "does it
  bite" to the 30-second election-verification window is sized against an
  assumption, propagation delay is small in practice, that nothing in this
  design measures, because nothing has talked to Drive yet.

---

## 12. Two M0 claims that were false from the start

Recorded for the same reason budgets-design §2.2 records the slice-8
measurement correction: the interesting part is not that a claim was wrong, it
is how long it stood without anything testing it.

Both were written in M0, as scaffolding, with no implementation in front of
them. Neither is a measurement that later drifted. Both were wrong at the moment
they were typed, and both survived three milestones — one in a KDoc that is the
first thing any reader of `:core:sync` sees, one in the README's second headline
feature — because nothing in the project ever had cause to execute them.

**"amount, date, description → remote wins (they came from the bank)."**
`ConflictResolver.kt`'s KDoc. No bank has ever talked to this app and none is
planned; there is no bank-side anything to be authoritative. Every field it
names is produced by a *device* — typed by the user, or interpreted out of a CSV
by that device's importer — and csv-import-design §2 exists precisely because
two devices can interpret one file into two different real dates. The rule would
have let whichever device synced second silently overwrite a date the other
device's user had confirmed on screen. This is not a heuristic that needed
tuning. It is a justification naming a party that does not exist. §5.1, §5.2.

**"deletion → deletion always wins over an edit."** Same KDoc. This one
contradicted behaviour that had *already shipped* by the time it was read:
`TransactionDao.update` clears `import_batch_id`, which is the deliberate
decision that a hand-edited row leaves its import batch and therefore survives
that batch's undo. Delete-wins honours that rule on one device and inverts it
across two — the row the local rule protects is exactly the row the sync rule
would delete. The contradiction was checkable against a file in this repo at any
point after M2. §5.3.

**What it cost, and the practice it argues for.** Nothing yet, because M4 is the
first milestone to read these lines and it read them before implementing. The
cost is in the near miss: slice 4 could have implemented the KDoc as written,
the field policies would have *looked* deliberate because they were written
down, and the first symptom would have been a user losing a date they had
confirmed — a class of bug that leaves no trace, because the overwritten value
is simply gone.

Stated as a rule rather than a story: **an interface with no implementation has
never been tested, and its comments carry a confidence its code has not
earned.** A design pass over inherited scaffolding owes each claim the question
of what evidence it ever had. Three corrections now sit alongside each other in
this repo — a transient measured from too small a sample, and two claims
measured from nothing at all. The M0 sketch's *shape* was right and is kept
(§5.1's closing paragraph); its stated reasons were not.

---

## 13. Proposed slices

M4a — slices 1–8. Each compiles and passes `checkModuleBoundaries` on its own.

1. **Categories become syncable** (§1.2). **Done.** `MIGRATION_3_4`: the four
   columns on `categories`, `deleteUserCategory` becomes a guarded soft delete
   that cascades to budgets and rules in one transaction, every category query
   gains `deleted_at IS NULL`, and `observeUncategorisedSpend` counts a
   transaction whose category was deleted. No sync code. Independently correct
   — it closes a rule-7 violation that exists today, and the cascade and the
   uncategorised query are behaviour that would otherwise have regressed.
2. **Stable identity** (§3). **Done.** Budget ids derived from the natural key;
   `contentHashOf` moves to SHA-256 and lives in `:core:model` so the migration
   and the repository cannot drift; `CSV_IMPORT` transaction ids become
   `h1:<hash>#<n>`. `MIGRATION_4_5` changes no schema at all and rewrites data,
   so every test asserts on values. The riskiest slice — it rewrites primary
   keys — and contained, because no foreign key targets either table.
3. **The op model and the shadow** (§4, §9). Split, because the three
   bookkeeping bugs in §4.3 are independent of the op format and were fixed
   first: **3a is done** — the guarded `markSynced`, `RowRevision` and
   `revisionOf` on all four DAOs, and `upsert` marking transactions pending.
   **3b is done** — `SyncOp`/`OpBatch`/`SyncTable` and their serialisation,
   `MIGRATION_5_6` with the `sync_shadow` and `sync_conflicts` tables and DAOs,
   and the entity —> field map mapping in `:core:data`. Settled while
   building it: when a shadow row is written and what the first sync does with
   a row that has no base (§4.1), and who reads `sync_conflicts` and what
   removes a row from it (§5.6). No resolution logic, no transport.
4. **The resolver** (§5). **Done.** `ConflictResolver` reshaped, per-entity field
   policies, the field-coverage test, and every pure unit test for the merge
   rules. The slice most worth independent review — the equivalent of
   column-role inference in M2 and `applyRules` in M3.
5. **The engine** (§4.2, §6.2). Pull → classify → merge → apply → push, each
   batch in one Room `@Transaction`, cursor handling, dirty-row derivation,
   idempotence. `SyncTransport` + `InMemoryTransport`. First slice where two
   engines can talk. **5a is done:** dirty-row derivation on all four tables —
   `dirtyRows`/`markSynced` on every DAO and repository, derived from
   `local_revision` against the shadow rather than `pending_operation` (§4.3),
   plus the `categories.updated_at` gap found and fixed along the way
   (`MIGRATION_6_7`). §4.3's guard is now load-bearing on all four tables, not
   just documented on `TransactionDao`. **5b is done:** the `SyncTransport`
   interface and `InMemoryTransport`, the mutable-list-behind-a-mutex fake
   §10.1's two-device harness needs, plus the abstract `SyncTransportContractTest`
   from §10.4 so the same per-device-cursor guarantees get checked against
   whatever implements the interface next (M4b's Drive transport, manually).
   **5c is done:** the pull/classify/merge/apply/push loop. `SyncEngine`
   (`:core:sync`) does the pull (cursor bookkeeping, skipping unreadable
   batches without re-fetching them) and the push (dirty rows from all four
   repositories into one batch, guarded acknowledgement); classify, merge and
   apply run as one Room transaction in `:core:data`'s `SyncApplier`, reached
   from `:core:sync` through the `RemoteMerge` seam documented in §9's
   corrected module-placement note — that note is where the module split
   settled, one level lower than this paragraph originally put it. Idempotence
   is a revision watermark, not a content diff: an op no newer than what
   `sync_shadow` already recorded for that row is skipped before the resolver
   is ever called. Found and fixed while building it: `local_revision` needed
   a Lamport-clock-style rebase across devices rather than a plain `+ 1`, and
   `created_at`'s "resolved as `min(local, remote)`" claim in §4.1 was not
   literally buildable — both corrected in place, next to what they correct.
   `SyncApplierTest` (`:core:data`, real Room) and `SyncEngineTest`
   (`:core:sync`, `InMemoryTransport` plus recording fakes) cover the two
   halves. Not yet built: the two-device harness itself (slice 6) — nothing
   has run two `SyncEngine`s against one transport and asserted convergence.
6. **The convergence suite** (§10.1, §10.2). **Harness, all fourteen scripted
   scenarios and the §10.3 property test are done** (`core/sync/src/androidTest/
   .../convergence/`); the CI-vs-nightly seed-count split and the §6.2 budgets-
   transient re-measurement are not -- see below. Two real bugs surfaced by
   building the harness, both pre-existing in already-committed code and both
   fixed in this slice rather than deferred: the missing push-acknowledgement
   shadow write (§4.1's correction) and `resolveDeletionVsEdit`'s missing
   "unchanged, not edited" case (§5.3's correction). Deliberately breaking the
   Lamport rebase found in slice 5c (reverting `SyncApplier.decide` to a plain
   `+ 1`) failed 41 of 50 property-test seeds, each with a concrete dropped
   edit and a reproducible seed number; deliberately making one field's policy
   asymmetric (`amount_minor` to `REMOTE_WINS`) did **not** reproduce as a
   convergence failure, for the reason recorded in this slice's §4.1
   correction -- worth knowing before trusting an asymmetric policy change as
   "probably caught by CI" the way the Lamport regression demonstrably would
   be. Pulled forward from slice 7 at the assigning task's request, because
   the property test is the piece that makes the README's convergence claim
   checkable rather than merely designed; slice 7 still owes the 50-seed/
   1,000-seed CI/nightly split (this slice's property test runs 50 seeds
   every time, with no nightly tier yet) and the §6.2 re-measurement.
7. **The horizon, and the property test's remaining CI shape** (§10.3, §7).
   **Done.** The CI/nightly split: `ConvergencePropertyTest` reads its seed
   count from `InstrumentationRegistry` (`DEFAULT_SEED_COUNT = 50`),
   `core/sync/build.gradle.kts` wires a `seedCount` Gradle property into
   `testInstrumentationRunnerArguments`, `ci.yml`'s existing `instrumented`
   job runs the default on every push, and a new `nightly.yml` runs
   `-PseedCount=1000` on a daily schedule (`workflow_dispatch` too) against
   just this one test class. The tombstone horizon: `SyncTransport.snapshot()`,
   `InMemoryTransport.compact()` as a test-only compaction simulation (real
   compaction is still M4b/slice 9), `SyncEngine`'s per-cycle horizon check
   and reconcile-then-resume-pulling flow, `SyncApplier.reconcile` (reuses
   `apply`'s merge machinery for rows the snapshot still knows about, and
   implements §7's "decide by local_revision versus shadow" for rows it
   doesn't), and `TombstoneReaper` reaping child tables before `CATEGORIES`
   for `ON DELETE CASCADE` safety. One scripted scenario added
   (`bDeletesAndCompactsBeforeAPulls_aReconcilesAndHardDeletes`) alongside
   the fourteen from §10.2. §7 has the full write-up and the one limitation
   this slice knowingly left open. The §6.2 budgets-transient re-measurement
   named in slice 6 as owed was not picked up here either -- closed out
   afterward, as a standalone item, once that gap was noticed; §6.2 has the
   result.
8. **Sync UI** in `:feature:settings` (§5.6). **Done.** The count on the
   Settings row, the conflict list and the restore path -- `SyncConflict`/
   `ConflictValue` (`:core:model`), `SyncConflictRepository`/`RestoreOutcome`
   (`:core:data`, `SyncConflictDao.getById` added for the read the restore
   path needs), `SettingsViewModel`/`SettingsScreen` (`:feature:settings`,
   the module's first real screen). One correction from this section's
   original plan: this milestone's own `SyncStatus` (Idle/Running/Failed,
   `:core:model`) turned out to be the wrong shape after all -- it is
   `SyncEngine`'s run-state, and nothing in the app ever calls
   `SyncEngine.sync` (no caller exists before M4b's transport), so it would
   read Idle forever and be decorative rather than informative. The count
   this row actually shows is `observeUnacknowledgedCount`, which has real
   data behind it regardless of whether anything has ever synced.
   `SettingsScreen` is reached from a top-bar action on all three tabs
   (`onOpenSettings`, wired in `BahiNavHost`) rather than a fourth tab, per
   `TopLevelDestination`'s existing note. Screenshots: `settings-empty.png`
   (what the shipped app actually shows, since nothing produces a conflict
   yet) and `settings-conflicts.png` (two seeded conflicts, captured against
   a real running build with the database hand-seeded to exercise the state
   -- restoring one on-device confirmed the write lands: `category_id`
   changed, `local_revision` bumped, the conflict acknowledged). One
   deliberate simplification, not attempted here: `category_id`'s value
   renders as the raw id, not the category's name. §11 has the sharper
   version of that gap -- the screenshot's own category ids are the seeded
   system slugs, which is a flattering case, not the general one.

M4b — slice 9. **Deferred, not next** (§2, D3): it is a milestone's worth of
work on its own, none of the hard part lives in it, and M4a is complete and
provable without it. Sketched here so M4a's interfaces are shaped for it, and
sketched in more detail below than the M4a pass had reason to go into,
because this document's M4b design pass (2026-09-01) is what turned §8's open
questions into decisions (D9, D12, D13).

9. **The Drive transport** (§8). **Deferred, not next** (§2, D3). Nine
   sub-slices rather than one, each independently reviewable and, where
   possible, independently shippable:

   - **9a — The disabled state and the setup path** (§8.5, D12). **Done.**
     `SyncConfiguration` (`:core:sync`) is the seam: `:app`'s build script
     turns whether `sync.properties` exists into `BuildConfig.SYNC_CONFIGURED`
     (library modules here don't generate one, so `:app` is the only place
     that can), and `AppSyncModule` binds it. `DisabledSyncTransport` is bound
     unconditionally in `SyncModule` — there is only the one `SyncTransport`
     implementation to choose between until 9e, configured or not.
     `SettingsUiState.syncConfigured` carries the answer on every state,
     `Loading` included, rather than defaulting it and risking a
     correctly-configured build showing the wrong row for one frame; the
     Settings screen renders a "not set up on this build" row above whatever
     the conflicts section shows. `SettingsScreenTest` covers both the row's
     presence and its absence, and `docs/sync-setup.md` has the setup path.
     Ships with no OAuth code at all, exactly as scoped. Verified against a
     running debug build, not just the test suite: `settings-empty.png` and
     `settings-conflicts.png` both changed the moment this landed, because
     the default build (no `sync.properties`) is the common case and both
     screenshots predate the row — both recaptured against the app running
     on-device, the second with a conflict hand-seeded directly into
     `sync_conflicts` the same way slice 8's original capture did.
   - **9b — The import-result line** (§6.1). **Done.** `existingRowsByHash`
     replaces `countExistingHashes`, returning `ExistingHashRow` (hash, id,
     `deleted_at`) instead of a bare per-hash count; `importBatch` now
     returns `ImportBatchOutcome` and tags each quota match `LIVE` or
     `TOMBSTONED` as it consumes it, with an insert-time id collision folded
     into `duplicatesSkipped` (it can only be a live-id collision, since the
     quota step already claimed every tombstoned match) so the three
     buckets — inserted, `duplicatesSkipped`, `previouslyDeletedSkipped` —
     never leave a row uncounted. The split threads all the way up through
     `ImportBatchResult` and `ImportResult` to the import screen's new line,
     shown only when the count is nonzero, exactly as designed above. No
     dependency on the rest of M4b, confirmed while building it — the fix
     touches `:core:database`, `:core:data`, `:core:importer` and
     `:feature:import` only, with `TransactionDaoTest` covering the live/
     tombstoned split against real Room and `DefaultCsvImporterTest` covering
     that the importer trusts the repository's counts rather than
     re-deriving them.
   - **9c — Encryption** (§8.4, D9). **Done.** `OpBatchCipher` (AES-256-GCM,
     `:core:sync`) encrypts/decrypts one `OpBatch` at a time against a
     `SecretKey`, deliberately with no `SyncTransport` in the loop — see the
     correction two paragraphs above §8.4's original sentence on why the
     envelope carries no salt. `PassphraseKeyDerivation` (PBKDF2WithHmacSHA256,
     210,000 iterations, OWASP's current floor) turns a passphrase and a salt
     into that key. `KeyWrapper` is the seam over `AndroidKeyStore`
     wrapping/unwrapping, exactly like `SyncConfiguration`'s seam over
     `BuildConfig` in 9a — the real `AndroidKeyStoreKeyWrapper` is verified
     on-device (`AndroidKeyStoreKeyWrapperTest`, real `AndroidKeyStore`, real
     round-trip and real tamper failure), and everything built on top of it is
     tested against `FakeKeyWrapper` instead. `SyncEncryptionKeyStore`
     orchestrates: derive, wrap, persist to `DataStore` (via a new
     `UserPreferencesDataSource.syncEncryptionKeyMaterial`, read as one
     combined value so the three fields making it up can never be observed
     half-written) and, on the way back, unwrap. `DataStoreModule`
     (`:core:datastore`) is new — nothing had provided a `DataStore<Preferences>`
     to Hilt before this, since `lastSyncCursor` has had no caller since M0
     either (§8.3).

     **What "pairing a new device" means without 9e.** `DriveTransport` does
     not exist yet, so there is no channel for a second device to learn the
     first device's salt automatically. `PairingCode` (base64 of the salt, not
     secret the way the passphrase is) is the honest, fully-functional version
     of that flow available today: `PassphraseScreen`'s setup path shows it
     after setup completes, and a second device's pairing path takes it as
     typed or pasted input alongside the passphrase. When 9e lands,
     `DriveTransport` publishing this string unencrypted alongside the op log
     replaces the manual copy — the seam (`SyncEncryptionKeyStore.pair`) does
     not change shape, only what supplies its `salt` argument.

     `PassphraseScreen`/`PassphraseViewModel`/`PassphraseUiState`
     (`:feature:settings`) are the setup-and-pairing UI, reached from a new
     "Encryption" row on Settings shown exactly when `syncConfigured` is —
     the row does not itself know whether a key already exists, deliberately,
     so there is exactly one place (`PassphraseViewModel`'s own `isSetUp`
     check) that can be wrong about it. `PassphraseUiState.Loading` covers the
     one genuinely async read this screen needs (unlike `syncConfigured`,
     `isSetUp` has no synchronous answer), so an already-configured device
     never flashes the entry form before landing on `Done`. The lost-passphrase
     warning is the exact wording §8.4 commits to; the setup-time disclosure
     paraphrases §8.4's threat-model bullets rather than quoting them verbatim
     (only the lost-passphrase line was ever a verbatim commitment — the
     bullets' commitment was to the reasoning, not the exact sentences).

     Covered by `OpBatchCipherTest`, `PassphraseKeyDerivationTest`,
     `PairingCodeTest` (all pure JVM, no transport, exactly the "testable in CI
     as a pure byte-transform" claim this entry made before it was built — a
     round trip, two different encryptions of the same batch producing
     different envelopes, a wrong key failing loudly via
     `WrongPassphraseException` rather than returning garbage, and a tampered
     envelope failing the same way), `SyncEncryptionKeyStoreTest` (real
     file-backed `DataStore`, `FakeKeyWrapper`), `AndroidKeyStoreKeyWrapperTest`
     (androidTest, real `AndroidKeyStore`), `PassphraseViewModelTest`
     (Turbine, `FakeSyncEncryptionKeyStore`), and `PassphraseScreenTest` plus
     new `SettingsScreenTest` cases (androidTest, real Compose). Also verified
     against a running debug build with `sync.properties` present: set up
     encryption end to end, confirmed the real pairing code renders, force-
     stopped and relaunched the app, and confirmed the same pairing code comes
     back immediately from `Done` rather than the entry form — the one thing
     no test suite here proves, since it depends on a real `AndroidKeyStore`
     key surviving a real process death, not a fake standing in for one.
   - **9d — OAuth** (§8.6). **Done.** `PlayServicesDriveAuthorization`
     (`:core:sync/oauth`) wraps `Identity.getAuthorizationClient` behind
     `DriveAuthorization`, the same seam shape `SyncEncryptionKeyStore` uses
     over `AndroidKeyStore` (slice 9c): the real class needs Play Services and
     a Google account, so `SettingsViewModel` and the Drive connection row are
     tested against `FakeDriveAuthorization` instead.
     `dev.charanjeev.bahi.core.model.SyncStatus.NeedsReauthorization` exists,
     as this document said it would, but nothing produces it yet — same
     "decorative until 9g" note slice 8 already made for the rest of
     `SyncStatus`, restated at the new case rather than left to be
     rediscovered. What the Settings row actually reads is
     `DriveAuthorization.connectionState`
     (`NOT_CONNECTED`/`CONNECTED`/`NEEDS_REAUTHORIZATION`), for the same
     reason slice 8 preferred `observeUnacknowledgedCount` over `SyncStatus`
     there: it is live from the moment a device first tries to authorize, not
     only once a sync cycle exists to update it.

     **Corrected while building 9d: `androidx.credentials` is not needed.**
     §8.6's "plus whatever `androidx.credentials` artifacts it needs" was a
     hedge from before this was built. Checked against Google's own current
     documentation rather than assumed: that artifact backports the newer
     Credential Manager *sign-in* flow to older API levels and has nothing to
     do with the Authorization API's *scope-grant* flow this app actually
     uses. The only new dependency is `com.google.android.gms:play-services-
     auth:22.0.0`.

     **A bug found in review before this shipped.** The Drive row's first
     draft rendered inside the `Empty`/`Success` branches unconditionally,
     independent of the `if (!syncConfigured) ... else ...` block above it
     that gates `EncryptionRow` — so an unconfigured build would have shown
     "Not set up on this build" *and* a live "Connect Google Drive" button
     with nothing for it to connect to. Fixed before it reached a test run:
     the row's own doc comment now says why it has to share `EncryptionRow`'s
     gate, and `notConfigured_hidesTheDriveRow` is the regression test.

     **What is and isn't verified, stated as plainly as the task asked for.**
     Everything except `PlayServicesDriveAuthorization` itself is unit- or
     Compose-tested against `FakeDriveAuthorization`: the ViewModel's
     `onConnectDriveRequested`/`onAuthorizationResult`, every
     `DriveConnectionState` the row can render, and the gate bug above. That
     one class cannot be exercised by any automated test in this repo —
     `DriveAuthorization`'s own doc says why — and this environment has no
     Google account and no way to create one, so a real consent *grant* was
     never completed either. What manual verification on the slice-9c emulator
     did show, confirmed in `logcat` by real component names rather than
     assumed: tapping "Connect" genuinely calls
     `Identity.getAuthorizationClient(context).authorize(...)`, genuinely gets
     back a resolution and a `pendingIntent`, and that `pendingIntent`,
     launched through `SettingsRoute`'s `rememberLauncherForActivityResult`,
     genuinely opens Google's own account-add UI and returns cleanly to
     `NOT_CONNECTED` on cancel, with no crash. So the wiring is verified live;
     only the inside of a completed grant — the `Authorized` branch, and the
     exact `ApiException` codes a real revocation returns — rests on Google's
     documented contract rather than on having watched it happen.
     `PlayServicesDriveAuthorization`'s own doc has the same account, in the
     place a future reader is most likely to look for it.
   - **9e — `DriveTransport` and its contract test** (§10.5). Done.
     `DriveApi` (`core/sync/drive/`) is the four REST calls, list/get/create/
     delete, all scoped to `appDataFolder`; `DriveTransport` builds `push`/
     `pull`/`snapshot` on top of it and is the one place in the app that
     actually calls `OpBatchCipher` — `SyncTransport.push` takes a structured
     `OpBatch`, not bytes, so encryption can only run where an `OpBatch`
     first turns into bytes to leave the device, which is here, not above it.
     A device with no key set up refuses both operations loudly via
     `DriveTransportException` rather than ever writing or reading plaintext.

     **Does `SyncTransportContractTest` run against Drive? Yes**, exactly as
     §10.5 committed to: `DriveTransportContractTest` (`core/sync/src/
     driveTest/`) subclasses it and overrides only `createTransport()`,
     wiring a real `DriveTransport` against a real, already-authorized
     throwaway Drive account — the same eight checks `InMemoryTransportTest`
     runs, with zero new test logic, against the real backend. It is never
     run by CI: the source directory is only added to the `test`
     AndroidSourceSet when `core/sync/drive-test.properties` (gitignored)
     exists, so on a fresh clone the sources aren't merely un-run, they are
     never compiled. **What stands in for CI, and what doesn't exist to
     stand in at all:** `DriveTransportTest` — a second, separate subclass of
     the same `SyncTransportContractTest`, living in the *ordinary* `test`
     source set — wires `DriveTransport` to `InMemoryFakeDrive`, a
     hand-written fake `okhttp3.Call.Factory` backend that actually stores
     and serves files rather than verifying calls happened. It runs on every
     `unitTests`/CI pass and proves `DriveTransport`'s own logic (cursoring,
     encryption, appProperties tagging, pagination, error classification)
     satisfies the contract; it cannot prove anything about eventual
     consistency, quota, or Drive's real query semantics, which is exactly
     what `DriveTransportContractTest` exists to check separately and
     manually. Nothing stands in for a real account at all — that gap is
     named, not hidden, the same way slice 9d named what a real consent
     grant could not be verified against.

     **The salt, published unencrypted — `SyncEncryptionKeyStore`'s doc
     named this as this slice's job, and it's built:** `DriveTransport.
     publishSalt`/`readPublishedSalt` write and read one small plaintext
     file, tagged separately from the encrypted op log, so a second device
     can find the first device's salt instead of being told it by hand. This
     is additive only: `PassphraseScreen`'s manual pairing-code flow (slice
     9c) is untouched. Nothing calls `readPublishedSalt` from that screen
     yet — replacing a pasted code with automatic discovery is a UX decision
     for whoever picks that up next, deliberately not assumed here.

     **`snapshot()` always returns the empty default.** No writer of a
     compacted snapshot exists until 9f, which also owns the wire format one
     would use — inventing that format now, ahead of the code that first
     writes one, would be guessing both sides of a contract that slice hasn't
     set yet. This still satisfies `SyncTransportContractTest`'s only check
     on this method (`snapshot of a transport nothing has compacted has an
     empty horizon`) exactly. **No longer true as of 9f:** `snapshot()` now
     reads the real `kind=snapshot` file `DriveCompactor` writes; see that
     slice's entry.

     **`SyncModule` still binds `DisabledSyncTransport` unconditionally.**
     `DriveTransport` exists, fully implemented and tested, but nothing
     constructs it via Hilt yet. This is a deliberate scope line, not an
     oversight: `SyncEngine` has no caller anywhere in the app until 9g, so a
     conditional binding today would be reachable by nothing — the same
     "complete but not yet wired to run" state `SyncEngine` itself has been
     in since M4a. Making the binding conditional on `SyncConfiguration.
     isConfigured` is left for 9g, so it can be wired and manually verified
     together with the code that first actually calls it, rather than
     sitting inert in between.

     **Two corrections found while building this, neither in the design
     pass's control:**
     - OkHttp 5.5.0 (approved mid-session for the HTTP client) turned out to
       need `compileSdk` 37 for its Android AAR variant — it added Encrypted
       Client Hello support in that release, gated on Android API 37, and
       Gradle's AAR-metadata check fails a build compiled against 36 (this
       repo's setting) rather than silently ignoring it. Checked against
       OkHttp's own changelog rather than guessed: 5.4.0 predates that
       requirement and has no other Android-relevant change back to 5.0.0.
       Pinned to 5.4.0 in `gradle/libs.versions.toml` instead of bumping
       `compileSdk` project-wide, which would have been a much larger,
       unrequested footprint for one dependency.
     - AGP has no supported way to add a custom-named test source set to an
       Android library module — unlike a plain `java-library` module,
       `sourceSets` here is AGP's `AndroidSourceSet` container, which only
       wires compile/test tasks for build-type/variant-shaped names.
       `driveTest` is instead extra source directories on the *existing*
       `test` source set, conditionally added, with a `driveTest` task built
       by hand out of `testDebugUnitTest`'s own already-configured classpath
       rather than a from-scratch compile task. `core/sync/build.gradle.kts`
       has the full reasoning; docs/sync-setup.md has the developer-facing
       setup steps for both this and 9d's OAuth client (also corrected
       there — see that document).
   - **9f — Compaction** (§8.3, D13). **Done.** `DriveCompactor`
     (`core/sync/drive/`): D13's single-elected-compactor, the snapshot
     write-then-delete sequence, and the grace period and threshold trigger
     from §8.3's original mitigation list. Tested entirely against
     `InMemoryFakeDrive`, the same offline fake `DriveTransportTest` already
     used — this is the piece that makes compaction's correctness checkable
     by CI on every push rather than something only a manual `driveTest` run
     against a real account could exercise, which is what made it worth
     building this way rather than deferring it to a manual-only proof the
     way `DriveTransportContractTest` had to be for the transport itself.

     **Election.** `electIfNeeded()` implements D13's claim-then-verify: no
     `owner.json`-equivalent (a single `kind=owner` file, `appProperties`
     carrying `deviceId`/`claimedAt` rather than a JSON body — consistent with
     how `DriveTransport` already tags `ops`/`salt` files, so `DriveCompactor`
     never needs to download a file's content just to learn who claimed it)
     means this device claims it, waits, then keeps only the
     lexicographically-lowest `deviceId` among whatever now exists — the same
     tiebreak shape §5.5's conflict resolver already uses, reused rather than
     invented fresh. Once any owner file exists, every later call is a single
     `list` and a string comparison: no wait, no write, which is what makes
     election "once per install" true in code and not just in the design
     prose. The wait itself (`electionWaitMillis`, default 30 000 like D13
     specifies) is a constructor parameter, not a fixed constant — no test in
     this repo can afford a real 30-second wait per case, and the number
     being unable to change per call site would have meant either that or a
     slow suite.

     **"Election has to work when the elected device is simply gone" — it
     does not, on purpose, and that is what `isStale`/`takeOver` are for.**
     `electIfNeeded` alone never re-elects a live-but-abandoned owner file —
     nothing about a device going quiet deletes its claim, so by design
     nothing here notices on its own. D13's own answer to that case is the
     manual Settings affordance, not automatic failover: `isStale()` answers
     whether it looks abandoned (gated on `TOMBSTONE_HORIZON_DAYS`, "the same
     horizon constant §7 already names," per D13), and `takeOver()` deletes
     the current claim and re-runs the identical claim-then-verify dance a
     first election uses. `DriveCompactorTest` proves this end to end: elect
     device A, advance the fake clock 91 days, confirm device B's `takeOver`
     succeeds and device A's own `electIfNeeded` now reports false — the
     "gone device" scenario, entirely reproducible offline, on every CI run.

     **`isStale` measures compaction, not the claim's age, and says why in
     its own doc.** The literal question — "has this snapshot been compacted
     recently" — is answered by the newest snapshot's age once one exists.
     Before any snapshot exists, claim age alone can't tell a genuinely quiet
     account (never enough ops to cross the threshold, not stale) from an
     abandoned one apart, so that branch also requires an actual op-file
     backlog past the threshold before it will call the case stale. Both
     branches, and the "quiet account is never flagged" property specifically,
     are their own tests.

     **Compaction folds forward, not from scratch.** A second and later round
     reads the previous snapshot (if any) via the new `DriveApi.latestSnapshot`
     seam shared with `DriveTransport.snapshot()` — the two classes agree on
     the file (`kind=snapshot`, highest `n`) and the envelope
     (`OpBatchCipher.encryptSnapshot`/`decryptSnapshot`, this slice's widening
     of 9c's cipher onto `RemoteSnapshot` as well as `OpBatch` — same
     envelope format, same failure mode on a wrong key) without one importing
     the other's internals — and merges newly-eligible op files onto it
     newest-revision-wins, the same fold `InMemoryTransport.compact()`'s
     test-only simulation already does for the in-memory case, extended here
     to not discard history a prior round already compacted away.
     `DriveCompactorTest` proves this directly: a first round crosses the
     threshold and writes `snapshot-1`; a second round's snapshot still
     contains every row the first one did, not just what the second round's
     own ops touched.

     **Grace period and threshold, both real gates, tested independently of
     each other.** `DriveApi.list`/`listAll` now request `createdTime` in
     their `fields=` param (a field they simply hadn't needed until this
     slice) so `DriveCompactor` can judge an op file's real age against the
     one-hour grace period rather than approximating it. A round with a
     large-enough op-file count but every file still within the grace period
     compacts nothing; a round past the grace period but below the op-file
     threshold also compacts nothing; and a round with some files past the
     grace period and some not folds and deletes only the eligible ones,
     leaving the rest for a later round — `DriveCompactor` deletes exactly
     what it read, matching §8.3's "delete-exactly-what-I-read" mitigation
     verbatim, not a coarser "delete everything listed."

     **Write fully before deleting, and re-verify ownership immediately
     before the delete, not just at the start.** `compact()` checks
     `isElectedOwner()` once before doing any work and again after the new
     snapshot has been written but before a single op file is deleted. The
     second check is defence in depth for the one narrow window D13's own
     analysis leaves open — a device that believed itself elected during the
     first-election race and loses the tiebreak partway through a compaction
     cycle — and the cost of losing that race after already writing is one
     harmless extra snapshot file, never a wrongly-elected device deleting
     files it was never entitled to.

     **The Settings takeover affordance is not wired to any screen.**
     `isStale`/`takeOver` exist and are tested, satisfying D13's requirement
     that the *logic* exist — what doesn't exist is a button. Wiring one into
     `:feature:settings` needs a real `DriveCompactor` instance, which needs a
     real `deviceId`, and nothing in this app generates or persists one yet —
     the same slice-8/M4b gap `SyncEngine`'s own `deviceId` constructor
     parameter has named as unresolved since M4a. Inventing a throwaway
     device identity just to wire one button would be scaffolding this
     document elsewhere refuses to build ahead of the code that actually
     needs it (§9e's `SyncModule` binding deferral is the same call, for the
     same reason). Whatever slice solves device identity — most likely 9g,
     since `SyncEngine` needs the same answer to get its first caller — is
     where the Settings screen should pick this up; `DriveCompactor`'s own
     class doc names this gap in the same place a reviewer reading the code
     would look for it.
   - **9g — The periodic worker** (§8.7). `PeriodicWorkRequest` at the 4-hour
     cadence, the foreground/Settings-open expedited trigger, the
     transient/terminal failure split, and the last-successful-sync display on
     the Settings row. **This is the slice that gives `SyncEngine` its first
     caller anywhere in the app** — everything before it in this list can be
     built and unit-tested without sync ever actually running end to end on a
     device.
   - **9h — The conflict screen's first real render** (§8.8). The
     `category_id`-to-category-name join this document now treats as required
     rather than deferred, and recapturing `settings-conflicts.png` against
     genuine two-device output once 9g makes that possible. Deliberately
     last: it depends on everything else in this list being wired together
     and running.

   Ordering within the list is not strict. 9a and 9b have no dependency on
   anything else and could go first, or ship standalone. 9c is independent of
   9d/9e. But 9f, 9g and 9h are a strict chain — compaction has to exist
   before the worker runs it repeatedly for long enough to matter, and the
   worker has to exist before there's real data for 9h to render. If M4b ever
   gets split further than this document schedules, that chain is where the
   boundary should fall.

---

## 14. Decisions

Question / options / recommendation / what happens if we pick wrong. D3 is the
one that changes everything else.

**Answered 2026-08-27.** D1, D2, D5, D6, D7, D8, D10 and D11 as recommended.
D4 as recommended, with the `h1:` version prefix explicitly retained as the
thing that keeps a description-derived id from being a one-way door. D3: split,
and **plan for M4a only** — build the convergence engine and stop before the
transport; M4b is recorded in the README roadmap as deferred, with the reason,
rather than as next. D9 is the only one left open, and it does not block M4a;
see its entry.

**M4b design pass, 2026-09-01.** D9 as recommended, (a) — now decided rather
than deferred; see its entry for what changed. D12 and D13 are new to this
pass, both as recommended. Nothing here is scheduled: M4a remains the only
milestone actually built, and the README roadmap's "deferred, not next" for
M4b is still accurate. This pass turns the sketch in §8 into a design that
could be sliced and estimated — not into a commitment about when.

### D1 — What syncs?

- **Options:** (a) transactions, categories, budgets and rules — everything the
  user can see. (b) transactions only. (c) user-authored data only (categories,
  budgets, rules, manual transactions) and not imported transactions.
- **Recommendation: (a).** Not really a choice: §1.2 shows categories are
  load-bearing for transaction sync because of the foreign key, so (b) is only
  reachable by nulling out categories on arrival — the exact silent discard the
  README says sync must not do. (c) fails for the same reason at one remove: an
  edit to an imported transaction references a row the remote never received.
- **If wrong:** the direction that would hurt is discovering that categories
  didn't need the tombstone work, which costs one additive migration and a
  soft-delete conversion that rule 7 arguably required anyway.

### D2 — Backend

- **Options:** (a) Google Drive `appDataFolder`, append-only op log. (b) hosted
  Postgres with RLS. (c) no backend — device-to-device or an exported bundle.
  (d) Firestore.
- **Recommendation: (a).** §8.2–§8.3: the privacy answer a finance app should be
  able to give, zero running cost, no CI credentials, and its weak semantics push
  the interesting work onto the device where it can be tested. (d) is
  disqualified specifically — it would outsource the problem the milestone exists
  to solve.
- **If wrong:** (a)'s failure mode is §8.3's compaction race, and the recovery is
  to swap the transport for (b). Because the engine is transport-agnostic by
  construction (D3), that swap is one module's worth of work and the merge logic
  and its tests move across untouched. Starting at (b) and regretting it is the
  expensive direction: it means having built an account system and taken custody
  of strangers' financial records first.

### D3 — Split M4 into M4a (engine) and M4b (transport)?

- **Options:** (a) split, build M4a first and completely. (b) one milestone,
  engine and Drive transport together. (c) transport first, against a trivial
  last-write-wins merge, and improve the merge later.
- **Recommendation: (a).** §2. The hard problem — the one the README claims — is
  entirely on-device, and M4a is fully testable with no credentials. (c) is the
  tempting one and the worst: shipping last-write-wins first means the wrong
  merge runs against real data, and every row it flattens is unrecoverable.
- **If wrong:** M4a alone ships nothing a user can touch, which in a portfolio
  repo can read as an unfinished milestone. Cheap to mitigate (name the
  convergence suite in the README's testing table, and give Settings a
  "not configured" row) and cheap to reverse — M4b is additive.

### D4 — Cross-device import duplicates

- **Options:** (a) accept them; add a duplicate-finder tool later. (b)
  content-derived ids for `CSV_IMPORT` rows, `h1:<hash>#<n>`, with
  `contentHashOf` upgraded to SHA-256. (c) an alias table linking rows two
  devices created for the same underlying transaction.
- **Recommendation: (b).** §3.1. It makes the duplicate impossible rather than
  detectable, reuses the count-aware quota that already exists, and makes local
  de-duplication and cross-device de-duplication one mechanism.
- **If wrong:** the risk is that ids become derived from a fragile string. The
  `h1:` version prefix is the mitigation and it is why this isn't a one-way door:
  a future scheme coexists as `h2:` and the only degradation across the boundary
  is falling back to (a). Choosing (a) now and regretting it is worse than it
  looks — by then a real user has 800 rows and every budget total is doubled.

### D5 — How is the merge base stored?

- **Options:** (a) a `sync_shadow` table holding the last-synced row. (b)
  per-field dirty flags on each row. (c) no base — two-way merge with per-field
  policy, which is what the M0 sketch implies.
- **Recommendation: (a).** §4.1. It requires **zero changes to existing write
  paths**, which is the property this codebase optimises for everywhere else. (b)
  makes every present and future write path responsible for remembering
  something; (c) cannot distinguish "I changed it" from "you changed it," which
  is the question conflict resolution is.
- **If wrong:** (a) costs a second copy of a few thousand small rows, and a lost
  shadow degrades to (c) — mitigated by making that state loud rather than silent
  (§4.1). Picking (c) and regretting it means every merge it ran was potentially
  a silent overwrite, and there is no record to reconstruct from.

### D6 — Concurrent delete versus edit

- **Options:** (a) the edit wins; the row survives. (b) the delete wins, per the
  M0 sketch. (c) prompt the user.
- **Recommendation: (a).** §5.3. Re-deleting costs one tap; a lost edit is not
  recoverable. And it is the only option that makes batch undo behave the same
  across two devices as it already does on one, given that `update` clears
  `import_batch_id`.
- **If wrong:** a user who deletes something on one device sees it return once,
  and deletes it again. That is the whole cost. (b)'s failure is silent data loss
  in exactly the scenario the README uses to justify this milestone.

### D7 — Notes

- **Options:** (a) the three-step rule in §5.5 — base-aware, then superset, then
  keep both. (b) last-write-wins. (c) three-way line merge with markers.
- **Recommendation: (a).** Never loses text; the ugly output is visible and
  editable.
- **If wrong:** the visible cost is an occasionally ugly note and a note that can
  grow across repeated conflicts. (b) loses a user's words silently, which is
  categorically worse than ugly. (c) puts `<<<<<<<` in a finance app's UI.

### D8 — Tombstone horizon

- **Options:** (a) 90 days, with a full-reconciliation path for devices that
  missed the window. (b) never clean up. (c) clean up aggressively (7–14 days).
- **Recommendation: (a).** §7. The reconciliation path is needed anyway for a new
  device, so the horizon adds a constant, not machinery.
- **If wrong:** too long wastes storage that is negligible at this scale; too
  short sends devices down the slow path often, or — if the path is buggy —
  resurrects deleted rows. The value matters much less than the path being a
  tested code path rather than a theoretical one, which is why (b) is not the
  safe-looking option it appears to be: it means the reconciliation path never
  runs and is never exercised.

### D9 — Encryption

- **Options:** (a) AES-GCM with a passphrase-derived key, in M4b's first slice,
  no new dependency. (b) rely on the provider's at-rest encryption and say so
  plainly in the app. (c) defer and add later.
- **Recommendation: (a), argued against properly, not just asserted.** §8.4
  now makes the case for (b) in full rather than only for (a) — the reasoning
  in the original pass here ("any app the user authorises for `drive.appdata`
  can read it") turned out to be factually wrong: `appDataFolder` is isolated
  per OAuth client, so no other app reaches it regardless of scope, and it's
  excluded from Takeout too. The corrected threat model is narrower — Google
  under compulsion, and anyone who takes over the user's Google account
  outright — and (a) still answers both of those correctly, so the
  recommendation didn't reverse the way the ₹0-budget call in budgets-design
  §2.1 did. What changed is the reason: not "other apps can read this," which
  wasn't true, but "a subpoena or a phished password shouldn't be enough to
  read this," which is. The compounding-cost comparison to `importBatchId`
  also doesn't fully hold on its own — that cost was pure engineering and
  free to the user; this one is paid by the user at setup, encryption or not
  — so it isn't cited as the reason by itself here anymore.
- **If wrong:** a passphrase the user must not lose, with no recovery. The
  mitigation is framing: it protects the synced copy, not the ledger — losing it
  costs sync, not data — and that has to be the literal wording in the UI. If
  that trade is unacceptable, (b) is defensible **only** if the app states
  plainly where the data goes and who can read it.
- **Status: decided, 2026-09-01 — (a).** M4a moved no data off the device, so
  D9 was correctly left open through that milestone. This design pass is
  M4b's, and M4b is the milestone that moves data, so the deferral no longer
  applies. §8.4 has the concrete shape settled now: a passphrase-derived
  AES-256-GCM key, cached behind `AndroidKeyStore` so the passphrase is typed
  once per device rather than once per sync, a new device paired by typing the
  same passphrase by hand — no in-app shortcut, deliberately, since any
  shortcut is itself a channel the passphrase would have to cross — and the
  two pieces of user-facing copy this document commits to verbatim, for setup
  and for the lost-passphrase case. The disclosure paragraph §8.4 already
  wrote ships regardless, as it would have under (b) too — that part was never
  what was open.

### D10 — Rule priority under concurrent reordering

- **Options:** (a) accept the garbling; it stays deterministic and convergent
  because budgets-design §1.5 already specifies a `(priority, id)` total order.
  (b) fractional indices.
- **Recommendation: (a).** §6.5. Real work for a list that holds a dozen rows,
  and correctness is not at stake — only intent.
- **If wrong:** a user reorders rules on two devices and gets an order neither
  chose. Visible, fixable by reordering again, and (b) remains available later as
  a column change plus a migration.

### D11 — `Transaction.merchant`: budgets-design D6's deadline arrives with no vehicle

- **The problem:** D6 resolved to keep `merchant` and drop it at the end of M4 if
  merchant extraction hadn't been built, "folded into whatever table rebuild M4's
  sync work needs anyway." §6.4: **M4 as designed needs no table rebuild.** Every
  change is additive. So the deadline arrives and the free ride does not exist.
- **Options:** (a) keep it; it now also needs a merge policy (trivially,
  last-write-wins) and travels in every payload as a null. (b) build merchant
  extraction as part of M4 — it would materially improve rule matching, and it is
  its own normalisation feature with its own silently-wrong failure modes. (c)
  drop it with a dedicated 12-step table rebuild (`minSdk` 26 has no
  `DROP COLUMN`).
- **Recommendation: (a) for the duration of M4, and re-decide with M5.** (c) is a
  rebuild of every transaction row to remove one always-null column, which is a
  bad trade on its own; (b) competes with the milestone.
- **If wrong:** the cost of keeping it is one line in a payload and one line in
  a policy map, and it stays cheap. The cost of dropping it only ever rises, so
  the decision should not be allowed to drift a third time — it gets an explicit
  re-decision in M5's design rather than another conditional deadline.

### D12 — What a fresh clone sees

- **Options:** (a) sync UI hidden until configured. (b) sync UI visible,
  disabled, with an explanation and a documented setup path. (c) a fabricated
  demo sync mode.
- **Recommendation: (b).** §8.5. The portfolio-project argument: hiding the
  feature most discussed in this document from the person most likely to read
  the document is the wrong direction to fail in, and a fabricated demo risks
  being mistaken for evidence in a repo that has been careful, throughout M4a,
  to keep "the convergence suite proves this" and "a reviewer can see this
  run" as separate claims.
- **If wrong:** (a) is a low-cost reversal — one conditional around a nav
  entry. (c) is not: unwinding a demo mode a reviewer may have already judged
  real sync by is more expensive than building it was.

### D13 — Compaction concurrency, now that it has to be more than a named risk

- **Options:** (a) single elected compactor, `compaction/owner.json`,
  claim-then-verify at first sync. (b) let any device compact, with §8.3's
  original mitigation list (threshold trigger, grace period,
  delete-exactly-what-I-read) and accept the residual race. (c) switch to
  backend option A (hosted Postgres, §8.2) specifically to get real
  compare-and-swap.
- **Recommendation: (a).** §8.3's M4b addendum. It collapses an every-cycle,
  indefinite-duration race into a once-per-install-lifetime one, at the cost
  of one Settings affordance for the case the elected device is lost. (c) is
  the option the original design named as the fallback if this bites in
  practice — right instinct, wrong moment: nothing has run against real Drive
  yet to justify concluding (b)'s mitigations are insufficient, and (a) is
  cheap enough that reaching for (c) first would mean paying the
  account-system cost §8.2 argued against before confirming it's needed.
- **If wrong:** if single-election itself turns out to be the wrong shape —
  the takeover affordance is fumbled, or the claim-verify window needs to be
  longer than 30 seconds in practice — the fallback is (b)'s mitigation list,
  already designed and not discarded by choosing (a); nothing about the op
  log's shape changes. (c) remains available after either, unchanged, because
  the engine is transport-agnostic by construction (D3).
