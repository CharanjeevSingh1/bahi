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
  second unbounded table next to the tombstones (§7). Acknowledged conflicts
  older than the tombstone horizon are deleted with them.

Surfacing: a count on the Settings sync row ("3 conflicts resolved — review"),
and a marker on the affected transaction row in the list. Not a notification —
budgets-design §2.5 already declined to add notification infrastructure for the
over-budget case, and nothing here is more urgent than that was.

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
   Re-measuring under sync-driven writes is a slice-6 item, and per CLAUDE.md's
   rule about this exact test, whatever number comes out gets stated with its run
   count.

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
- **Who can read it.** Without app-layer encryption: the user, any app they
  authorise for `drive.appdata`, and Google — who encrypt at rest but hold the
  keys, so they can decrypt, and will if compelled. With app-layer encryption:
  only someone holding the passphrase, which never leaves the device.
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

The costs are real:

- The user must type the passphrase on every device. That is the same flow every
  password manager uses, so it is familiar, but it is a step.
- **A lost passphrase is unrecoverable**, by construction. There is no reset. For
  a finance app whose local data is unaffected — the passphrase protects the
  *synced copy*, not the device — that is survivable, and it must be said in that
  exact way in the UI: losing it costs you sync, not your ledger.
- Debugging is harder; the remote becomes opaque blobs.

**Recommendation: encrypt, in M4b's first slice, not later.** Retrofitting means
re-encrypting everything already in Drive and re-pairing every device — the same
"the cost of not having it compounds" argument csv-import-design §11.1 used for
`importBatchId` and budgets-design §4.1 used for putting sync columns on tables
before sync existed. D9.

### 8.5 How a fresh clone builds and tests

Nothing secret is committed, and nothing secret needs to be.

- An OAuth client ID is not a secret — it ships inside the APK. What a fresh
  clone needs is its *own* client ID, registered in its own GCP project against
  its own debug-keystore SHA-1, because Google binds Android OAuth clients to the
  signing certificate. That is a documented ~15-minute setup in the README, not a
  credential handoff.
- Absent that, the build works and the app works. `SyncModule` reads an optional
  `sync.properties` (gitignored); when it is missing it binds a
  `DisabledSyncTransport`, and the Settings sync row says sync is not configured
  and why. Every offline feature — which is every feature — is unaffected.
- **CI never needs any of it.** `./gradlew unitTests` and
  `connectedDebugAndroidTest` exercise the fake transport, which is where all the
  convergence testing lives (§10). The one thing CI cannot cover is real Drive
  behaviour, and §10.4 says so plainly rather than pretending otherwise.

---

## 9. Data layer changes

New tables (`MIGRATION_3_4`, all `CREATE TABLE`; plus `ALTER TABLE ADD COLUMN` on
`categories`):

```
sync_shadow
  table_name       TEXT NOT NULL
  row_id           TEXT NOT NULL
  remote_revision  INTEGER NOT NULL
  payload          TEXT NOT NULL
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
  INDEX(row_id)
  INDEX(acknowledged_at)
```

`categories` gains `local_revision INTEGER NOT NULL DEFAULT 1`,
`remote_revision INTEGER`, `pending_operation TEXT`, `deleted_at INTEGER` — the
same four every other table already has.

Neither new table carries sync bookkeeping of its own: they are local records
*about* sync, and syncing them would be circular. That is the one place this
codebase's "every table gets the four columns" habit does not apply, and it is
worth a comment on each `@Entity` saying so, since the pattern is otherwise
uniform enough that their absence reads as an oversight.

**Module placement.** `:core:sync` already depends on `:core:model`,
`:core:common`, `:core:data` and `:core:datastore`, and already has WorkManager,
Hilt-Work and kotlinx-serialization wired up. No new module and no new edge:

- `:core:model` — `SyncOp`, `OpBatch`, `SyncConflict`, and a reshaped
  `FieldResolution`. `SyncMetadata` gains nothing; it is already right.
- `:core:database` — the two entities, their DAOs, `MIGRATION_3_4`, the schema
  JSON, `MigrationTest` cases, the guarded `markSynced`, and the category
  soft-delete changes.
- `:core:data` — shadow read/write, the reconciliation queries, and the mapping
  between entities and field maps. **The resolver's field policies live here or
  in `:core:sync`, not in a feature** — nothing in `:feature:*` may see an
  entity (rule 3).
- `:core:sync` — `ConflictResolver` and its policies, `SyncEngine`,
  `SyncTransport` plus the fake, the `CoroutineWorker`, and (M4b) the Drive
  transport.
- `:feature:settings` — the sync screen and the conflict list. This is the
  milestone that gives that module its first real screen; it is a stub
  composable today.

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
- **Nothing here handles a second currency or a second account**, because
  nothing in the app does yet. Sync is not the place to introduce either, but it
  is the place where getting them wrong later becomes expensive, because the
  wrong assumption is baked into ids.

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
   3b is `SyncOp`/`OpBatch` and their serialisation, `sync_shadow` and
   `sync_conflicts` tables and DAOs. No resolution logic, no transport.
4. **The resolver** (§5). `ConflictResolver` reshaped, per-entity field policies,
   the field-coverage test, and every pure unit test for the merge rules. The
   slice most worth independent review — the equivalent of column-role inference
   in M2 and `applyRules` in M3.
5. **The engine** (§4.2, §6.2). Pull → classify → merge → apply → push, each
   batch in one Room `@Transaction`, cursor handling, dirty-row derivation,
   idempotence. `SyncTransport` + `InMemoryTransport`. First slice where two
   engines can talk.
6. **The convergence suite** (§10.1, §10.2). The two-device harness and all
   fourteen scripted scenarios. Also the §6.2 re-measurement of the budgets
   transient under sync-driven writes, with its run count.
7. **The property test and the horizon** (§10.3, §7). Generator, seed corpus,
   nightly job; tombstone horizon and the full-reconciliation path.
8. **Sync UI** in `:feature:settings` (§5.6). Sync status from `SyncStatus`
   (which already exists and is already the right shape), the conflict list, and
   restoring a discarded value. Gives that module its first real screen. Note
   CLAUDE.md's screenshot rule: this is a new screen, so it needs its set
   generated in the same commit.

M4b — slice 9. **Deferred, not next** (§2, D3): it is a milestone's worth of
work on its own, none of the hard part lives in it, and M4a is complete and
provable without it. Sketched here so M4a's interfaces are shaped for it.

9. **The Drive transport** (§8). Authorization for `drive.appdata`, the
   `appDataFolder` op log, compaction, the encryption envelope, DI gating on an
   absent `sync.properties`, the transport contract tests, the WorkManager
   periodic worker, and the manual two-device test plan. Also the import-result
   line from §6.1 ("12 rows were previously deleted and were not re-added").

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
- **Recommendation: (a).** §8.4. Retrofitting means re-encrypting everything
  already uploaded and re-pairing every device — the same compounding-cost
  argument this repo has now used twice.
- **If wrong:** a passphrase the user must not lose, with no recovery. The
  mitigation is framing: it protects the synced copy, not the ledger — losing it
  costs sync, not data — and that has to be the literal wording in the UI. If
  that trade is unacceptable, (b) is defensible **only** if the app states
  plainly where the data goes and who can read it.
- **Status: open, and deliberately not forced.** Both (a) and (b) are
  defensible, the choice belongs to M4b, and M4b is deferred — M4a moves no
  data off the device, so nothing waits on this. What is **not** deferred is the
  statement itself: §8.4 now writes out what the data is, where it goes, who can
  read it and whose job encryption is, under either answer, and that paragraph
  ships whichever way this lands. A finance app that stores transaction history
  off-device owes its user that in words, not by implication.

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
