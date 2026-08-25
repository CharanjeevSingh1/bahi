# Budgets and rule-based auto-categorisation — design

M3. Covers what an auto-categorisation rule is, where rules run and how they're
kept from ever overwriting a user's own choice, budget scope and period
resolution, how the two features interact when a rule reclassifies a
transaction, and the schema/migration for both. No code here is meant to be
copy-pasted; it's there to make the reasoning checkable, the same way
`docs/csv-import-design.md` is for M2.

Related reading: `core/model/.../Transaction.kt` (`categoryLockedByUser`,
`isExpense`), `core/model/.../TransactionFilter.kt` (the "data layer only
sees dates" precedent this design leans on twice), `core/database/.../
TransactionDao.kt` (`update`, `softDeleteBatch` — both precedents for how a
write is guarded), `core/data/.../OfflineFirstCategoryRepository.kt` (the
"invariant lives in the repository, not a DB constraint" precedent).

Nothing for either feature exists yet: no `CategoryRule`/`Budget` model, no
tables, no repository. `:feature:budgets` is a stub composable. There is no
M0 interface to critique here the way §1 of the CSV doc critiques one — this
document proposes the shape directly and tries to be equally honest about
where it's shaky.

---

## 1. Auto-categorisation rules

### 1.1 What a rule is

A rule is a **merchant substring match against one category, nothing else**:

```
CategoryRule(
    id: String,
    categoryId: String,
    merchantContains: String,   // matched case-insensitively, trimmed
    priority: Int,               // lower runs first; see §1.5
)
```

Rejecting the other two options the task asks about, explicitly:

- **Regex.** A user-authored regex is a footgun twice over: it's easy to
  write one that matches far more than intended (`.` unescaped, no anchors),
  and a pathological pattern run over hundreds of transactions on every
  import is a real, if small, ReDoS-shaped performance risk for a feature
  with no technical audience assumption behind it. Substring match is
  strictly less powerful and that's the point — it's very hard to write one
  that surprises you.
- **Amount range.** Genuinely useful in the abstract ("₹500–₹2000 at this
  merchant is groceries, ₹5000+ is a one-off") but it's a second axis of
  matching with its own UI (two number fields, inclusive/exclusive, currency
  formatting) for a use case nobody has asked for yet. The schema doesn't
  preclude adding `minAmountMinor`/`maxAmountMinor` as nullable columns
  later — an additive migration, not a rewrite — so under-scoping this now
  is cheap to fix. See decision D1.

**Rules match on `description`, not on `merchant`** — the obvious answer is
the wrong one here. `Transaction.merchant` exists in the model and the
entity, but nothing in the app populates it today:
`DefaultCsvImporter.import()` hard-codes `merchant = null` (there's no
merchant column in `ColumnMapping` — csv-import-design.md never proposed
one), and the manual entry form doesn't expose a merchant field at all
(`TransactionFormViewModel.kt`: "Fields this form never shows — merchant,
source, id, createdAt"). A rule engine matching on `merchant` would be
matching a column that is `null` for every transaction in this app, and
every rule would match nothing — the feature would look built and do
nothing. So the matched string is `description`, uppercase-trimmed, the same
normalisation `contentHashOf` already applies to it. The engine in §4.3
reads `merchant ?: description` rather than `description` outright, but that
is forward-compatibility for a field that is presently always null, not a
live code path — worth stating plainly so nobody reads it as evidence the
field works.

**So should `merchant` be populated, or dropped from the model?** It is
currently a column nothing writes and nothing reads, which is dead weight in
a schema that now has real migrations against it — but the two ways out are
not symmetric, and neither is free. Populating it means *extracting* a
merchant from the description (stripping `UPI/`, `POS `, reference numbers,
trailing city names), because bank CSVs have no merchant column to map — the
merchant is embedded in the description string. That extraction is a real
normalisation feature with the same silently-wrong risk profile as
everything else in the CSV design, and it is also the single change that
would most improve rule matching, since `SWIGGY` matched against a clean
merchant beats the same substring matched against `UPI/SWIGGY*ORDER/
BANGALORE/423891`. Dropping it is the more expensive direction than it
looks: `minSdk` is 26, and `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35+
(Android 14 / API 34) — on API 26 removal means the full 12-step table
rebuild, migrating every row. Recommendation: keep the column, treat
merchant extraction as the named future slice that justifies it, and if that
slice hasn't been built by the end of M4, drop it then — folded into
whatever table rebuild M4's sync work needs anyway, never as a rebuild of
its own. This is D6 so it gets decided rather than drifting.

**Four layers on the blank needle, not one — and three of them were missing
until slice 7.** `merchantContains = ""` is the one input to this feature
whose damage is unbounded: `"".contains` is true of every string, so a blank
rule doesn't match nothing, it matches the user's entire history and files
all of it under one category. This document said creation should reject it;
until slice 7 nothing did, and `applyRules`' own filter was the only thing
standing between a user and that outcome. It is now, outermost first:

1. The rule editor's save action is disabled while the field is blank, so an
   empty rule cannot be submitted at all.
2. `RuleEditorViewModel.onSave` refuses independently of that flag, reading
   the field flows rather than the derived UI state — `canSave` is a property
   of a state object, and a redesign or a new entry point that stopped
   consulting it must not be one mistake away from persisting the rule.
3. `CategoryRuleRepository.upsert` throws. By the time a blank rule reaches
   it, both layers above have failed, so this is a programming error —
   crashing on one is strictly better than storing a rule that will
   recategorise everything the next time any trigger fires.
4. `applyRules` filters blank needles out of the rule set, so even a rule
   somehow already in the table never matches.

Only the first is meant to be what a real user ever meets. Each of the other
three is caught by its own test, because a layer nothing tests independently
is a layer that has already stopped working and not told anyone.

### 1.2 Where rules come from

**User-created only. No seeded starter set, including the Indian-merchant one
that's the obvious first idea.** Reasons it doesn't hold up on inspection:

- Merchant strings in bank CSV descriptions aren't standardised even within
  one bank, let alone across bank+card-network+payment-processor
  combinations — the same merchant shows up as `SWIGGY`, `SWIGGY*ORDER`,
  `Bangalore/SWIGGY/UPI`, or truncated to `SWIGGY BANGA` depending on the
  rail the transaction went through. A starter rule tuned against one
  sample export either matches far too narrowly (looks broken) or, worse,
  matches a substring that collides with something unrelated (looks
  confidently wrong).
- A rule the user didn't write, silently categorising a transaction they
  didn't review, is exactly the kind of "looks successful, is quietly wrong"
  failure mode the CSV design spends real effort avoiding elsewhere (the
  date-ambiguity refusal, the encoding-mismatch detection). A hand-written
  rule at least has a human who chose the string and can see it's theirs.
- It's an ongoing maintenance liability with no natural owner — merchant
  naming drifts, and a stale seeded rule doesn't announce itself as wrong,
  it just quietly stops matching or starts matching the wrong thing.

A better version of the same idea — surfacing a *suggested* rule after
noticing the user has manually categorised the same merchant string three or
four times — is a genuinely nice feature and a natural extension once rules
exist at all. It's not proposed for M3: it needs its own matching-frequency
tracking and its own "is this suggestion still good" lifecycle, and none of
that is needed for the core feature to work. See decision D2.

### 1.3 When rules run

Three triggers, no others:

1. **At CSV import time.** Freshly-imported rows are uncategorised by
   construction (§1.1). Immediately after `TransactionRepository.importAll`
   returns, `DefaultCsvImporter.import()` runs the engine over exactly the
   rows that batch just inserted. This is `:core:importer` calling into
   `:core:data`, which it already does for the repository call — no new
   module edge.

   **"Exactly the rows that batch just inserted" is load-bearing, not
   phrasing.** Rules must run *after* de-duplication and only over its
   output. A row de-duplicated away was never written — the copy already in
   the table is a different row with a different id — so matching rules
   against everything parsed would report recategorising transactions this
   import never created. The count would look plausible and nothing would
   error, which is this project's recurring failure shape. It also leaves the
   pre-existing copy alone, which is the right call independently: that row
   may carry a category the user chose, and re-importing a statement is not a
   reason to revisit it. This requires `importAll` to return *which* rows it
   inserted rather than only how many — `ImportBatchResult.insertedIds`.
2. **On demand**, via an explicit "Recategorise uncategorised transactions"
   action (a button, not a background job) that runs every active rule over
   every transaction with `categoryId IS NULL AND categoryLockedByUser = 0`.
   This is the only way a rule created *after* an import ever touches
   transactions that already exist.
3. **After creating or editing a rule**, via an explicit "Apply to existing
   transactions" action offered right after the save — not automatic. See
   §1.6 for why automatic is the wrong default.

**Not a trigger:** manual transaction entry. The entry form already asks the
user to pick a category as part of saving; auto-suggesting one from rules
while they type would be a reasonable future addition but has no schema or
architecture impact either way, so it's left out rather than speculatively
designed now.

**Not a trigger:** a periodic background re-run (WorkManager). The dependency
is already in the version catalog but nothing in the app uses it yet, and
there's no case here that needs unattended background work — the same
reasoning `:core:importer`'s doc comment gives for staying interfaces-only,
and §6 of the CSV design gives for not reaching for WorkManager at "hundreds
of rows." A rule change is a user action; the recategorisation it triggers
can be a user action too.

### 1.4 Enforcing `categoryLockedByUser` — two layers, not one

The task calls this out as the hard constraint, correctly — it's the one
place a bug in this feature would look like a bug in an unrelated one (a
user sets a category by hand, an unrelated rule run later silently reverts
it, and the bug report reads "my categories keep changing," not "the rule
engine has a bug").

**Layer 1 — the candidate set.** Every call site that feeds transactions into
the engine (`applyRules(rules, candidates)`, a pure function, §4.3) is
required to have already fetched `candidates` with `categoryLockedByUser =
false` as a query condition, not a filter applied after the fact. A locked
transaction never becomes a candidate in the first place.

**Layer 2 — the write itself, guarded in SQL, not in the caller.** This is
the layer that actually answers "what stops a future feature forgetting":
the *only* write path a rule (or anything acting like one) is allowed to use
is a dedicated DAO method whose `UPDATE` carries the guard in its `WHERE`
clause:

```kotlin
@Query(
    """
    UPDATE transactions
    SET category_id = :categoryId, updated_at = :updatedAt,
        pending_operation = 'UPSERT', local_revision = local_revision + 1
    WHERE id = :id AND category_locked_by_user = 0 AND deleted_at IS NULL
    """,
)
suspend fun applyRuleCategory(id: String, categoryId: String, updatedAt: Long): Int
```

If layer 1 has a bug and a locked transaction's id reaches this call anyway,
the `UPDATE` matches zero rows and does nothing — the guard doesn't depend on
the caller having gotten the query right upstream. This is the same shape as
`softDeleteBatch`'s `WHERE import_batch_id = :batchId AND deleted_at IS
NULL`: the invariant that matters is expressed as a condition on the one
write path allowed to touch the column, not as a rule a caller has to
remember. The return value (rows actually updated, Room fills this in for a
Kotlin-bodied DAO method automatically) is the same "report the honest
count" pattern `softDeleteBatch` already established — the recategorise
action reports how many transactions actually changed, which can be fewer
than how many the engine matched if some were locked.

`applyRuleCategory` deliberately does **not** touch `import_batch_id` the way
`TransactionDao.update` does — a rule-categorised row is still part of its
import batch (only a hand-edit, which is what `update` means, evicts a row
from batch undo). And it doesn't touch `content_hash`, because the hash
already excludes category by design (README: "the hash deliberately excludes
id, category and notes").

Anything that ever wants to set a category without user intent — this
feature or a future one — must go through `applyRuleCategory`, never
`update()` or `upsert()` directly. Worth a doc comment on the method saying
exactly that, matching the house style already on `update()`'s own comment.

### 1.5 Rule conflicts

Deterministic, not user-visible ambiguity: rules are evaluated in `priority`
order (ascending, user-orderable, ties broken by `id`) and the **first**
match wins — evaluation stops there, rules don't combine or merge. A
transaction that matches rule A ("SWIGGY" → Food) and rule B ("SWIGGY
DELIVERY" → Groceries) gets whichever rule sorts first; the user controls
that by reordering.

No rule-provenance UI ("categorised by rule X") is proposed for M3 — it would
help explain surprising results, but it's additive (a nullable
`categorizedByRuleId` column pointing at a table whose rows can themselves be
edited or deleted out from under it) and not required for the feature to
work correctly. Worth a future slice, not this one.

### 1.6 What happens when a rule changes

**Not automatic.** Editing a rule's `merchantContains` or `categoryId` never
silently re-touches already-categorised transactions. Two reasons, not one:

- It's the same "silent mass mutation" shape the CSV design explicitly
  avoided in the date-format and encoding cases — the failure mode isn't a
  crash, it's transactions quietly changing category in a background pass
  the user never asked for and has no record of.
- It composes badly with `categoryLockedByUser`: if a user notices their
  transactions shifting and the trigger was "I edited an unrelated rule
  three screens away," the app has taught them that editing a rule is
  dangerous, not that the app is reliable.

Instead, saving a rule offers the same explicit action as §1.3's on-demand
case, scoped to that one rule, with a preview count before committing
("this will recategorise 14 transactions — locked ones are never touched").
This mirrors the CSV import preview's consent model directly: show what's
about to happen before it happens, rather than doing it and reporting after.

**How the preview is kept honest, added in slice 7.** The count and the write
come from one object: `previewApplyToExisting` returns a
`RuleApplicationPreview` carrying the actual `transactionId -> categoryId`
assignments, and `apply(preview)` commits exactly those rather than matching
again. A preview that recomputed on confirm could show one number and write
another.

The preview carries a second number the original design didn't name:
`lockedSkippedCount`, how many transactions the rules matched but will not
touch. Without it, "12 transactions will change" reads as wrong to someone
looking at 15 that obviously match, and the lock — the guarantee this whole
feature is built around — stays invisible at the exact moment it is doing its
job. Counting them needs a second query, because layer 1's candidate query
structurally cannot return a locked row (§1.4), which is precisely the
property that makes it safe. `countLockedMatches` returns an integer and
never a map, so there is no route from that second query to a write; it
shares `applyRules`' matching internals so the two can't drift, because a
preview computed with different matching from the write it previews is worse
than no preview at all.

Scope differs between the two triggers, and the difference is deliberate.
"Apply to existing" (this section) runs over unlocked transactions in *any*
category — moving an already-categorised transaction is the reason someone
edits a rule, so a preview that only filled in blanks would never show the
change they were trying to make. "Recategorise uncategorised" (§1.3) runs
only over transactions with no category, because a user reaching for
"fill in the blanks" should not have to worry that it will also rearrange
categories they already chose.

---

## 2. Budgets

### 2.1 Scope: one budget per category per calendar month, no "overall" budget

```
Budget(
    id: String,
    categoryId: String,     // NOT nullable -- see below
    month: YearMonth,       // value class over "2026-08"
    limit: Money,
    currencyCode: String,
)
```

**`month` is a `YearMonth` value class, not a `String`.** This is the
`Money`-over-`Double` argument applied to dates. A raw String accepts
`"August 2026"` or `"2026-8"`, both of which construct fine, store fine, and
then match no transaction at all — the budget reads ₹0 spent forever rather
than failing, which is precisely the silently-wrong failure mode this
document refuses elsewhere (the date-format refusal in the CSV design, the
encoding detection, the `NULL`-uniqueness trap two paragraphs down). One
parse in one place makes the whole class of bug unrepresentable instead of
merely unlikely. `YearMonth` lives in `:core:model`, zero-pads the month so
the stored column still sorts lexicographically, and owns `dateRange()` —
the single place a month becomes a concrete `LocalDate` pair (§2.3).

The column stays `TEXT` holding `"2026-08"` (§4.1): this is a domain-type
decision, not a storage one.

**A limit of ₹0 is refused at creation, reversed from slice 8's first pass.**
"I intend to spend nothing on this" is a real intention, and that was the
original argument for allowing it. It is not a real *budget*: a zero limit
has no state except over budget, so the row is red from the first rupee and
stays red, and every part of the progress display is degenerate. The
intention is better served by having no budget for that category, which costs
the user nothing to express. `BudgetProgress.fractionOfLimit`'s zero-limit
guard stays regardless — it is defence, on the same standing as `applyRules`'
blank-needle filter: M4 sync will write budgets this device's editor never
validated, and a repository call is not obliged to go through a screen.

**Calendar month, not rolling.** A budget covers one named month
(`year_month`), full stop. Rolling windows ("the last 30 days") are a
different, harder feature — see §2.4, which is really the same question —
and nothing about the task's framing ("a budget for 'August'") asks for one.

**One budget per `(categoryId, yearMonth)`, not several concurrent ones.**
Editing a category's August budget changes the existing row's `limitMinor`;
it doesn't create a second one that now has to be reconciled against the
first. Simpler to store and, more importantly, simpler for the user: there's
only ever one number to look at for "Food, August."

**No "overall" budget (a budget not tied to any category) in v1 — and this
is the one place in this document that's an actual near-miss, not just a
design choice, so it's worth showing the wrong turn.** The obvious way to
add one is `categoryId: String?`, `null` meaning "overall." That's wrong,
and it's wrong in a way that wouldn't show up until it mattered: SQLite (and
every SQL uniqueness constraint) treats `NULL` as distinct from every other
`NULL` in a `UNIQUE` index. A `UNIQUE(category_id, year_month)` constraint
would silently stop enforcing "one budget per month" for exactly the
overall-budget case — a user could end up with three different "overall
August" budgets and no constraint would ever catch it, while the per-category
constraint keeps working perfectly for every other row, which is what would
make it easy to ship without noticing. Making `categoryId` non-nullable
sidesteps the whole class of bug rather than working around it. If an
overall budget is wanted later, the fix is a real sentinel row (the same
pattern `SystemCategories` already uses for `"uncategorised"`) — a real,
non-null id that means "overall" — not a null column. See decision D3.

### 2.2 What a budget compares against

**Expense transactions only, filtered by sign, not by category semantics.**
`Transaction.isExpense` is already `amount.isNegative` (`Transaction.kt`), so
a budget's spend is `SUM(-amount_minor)` over rows where `amount_minor < 0`,
`category_id` matches, `date` falls in the month, and `deleted_at IS NULL`.
A positive (income) transaction never contributes to any budget's spend,
regardless of what category it's filed under — a salary credit filed under
"Income" doesn't make the Income budget (if someone made one) show negative
spend, it shows zero, because the query never sees positive rows at all.
Nothing stops a user creating a budget against the "Income" or "Transfers"
system categories today; it'll just always read ₹0 spent, which is
confusing UI, not a data bug. Worth the budget-creation screen excluding
those two categories from the picker — a UI-layer decision, not a schema one.

**Uncategorised spending is flagged, not silently folded in or silently
dropped.** It structurally can't be "counted against" any budget, because a
budget requires a real `categoryId` (§2.1) and uncategorised transactions
have `category_id IS NULL` — they don't even reference the `"uncategorised"`
system-category row (CSV import sets `categoryId = null`, and nothing in the
app currently writes the literal `"uncategorised"` id onto a transaction).
The budgets screen shows a separate, explicit line — "Uncategorised: ₹X this
month, not counted toward any budget" — computed by its own query
(`SUM(-amount_minor) WHERE category_id IS NULL AND amount_minor < 0 AND date
BETWEEN ...`). Visible, not buried, same instinct as the CSV design's
"unmapped/failed rows shown as a count, not buried in the results."

**The uncategorised total travels with the budgets, in one value
(`MonthlyBudgets`), rather than as a second flow the screen fetches
separately.** This is a correction to the slice-6 plan, not a restatement of
it, and the reason is a state the original framing missed. A month with no
transactions at all and a month whose spending is *entirely* uncategorised
leave every budget reading ₹0 spent — correctly, in both cases, since
uncategorised money isn't attributable to any category. Those are different
months, though: one is empty, the other has real money in it that no budget
can see. The whole difference is carried by the uncategorised figure, so a
screen handed only the budget list has no way to tell them apart and would
render them identically. Bundling them means the figure that distinguishes
the two states can't be the one a caller forgot to ask for, and both numbers
arrive in the same emission rather than from two flows that can disagree
about which write they reflect.

Rendered, the two states are
[`budgets-no-transactions.png`](screenshots/budgets-no-transactions.png) and
[`budgets-all-uncategorised.png`](screenshots/budgets-all-uncategorised.png)
— the budget rows are pixel-identical and everything below them differs,
which is the whole point.
[`budget-editor-zero-limit-rejected.png`](screenshots/budget-editor-zero-limit-rejected.png)
shows the ₹0 limit being refused at creation, per §2.1.

The repository still runs two queries — uncategorised spending has no
category to join a budget on, so it can't be a column of the totals query —
and `combine`s them. That has one transient worth naming: both flows are
invalidated by the same write to `transactions` but re-query independently,
so combine can briefly pair one's new value with the other's old one, and
moving a transaction between a budget and the uncategorised line emits a
single intermediate frame where the two sides disagree about which write
they reflect. Removing it entirely would mean one query, which the paragraph
above rules out.

**Measured in slice 8, and re-measured in slice 9 after CI called the first
reading wrong.** `BudgetTotalsTransientTest` collects every emission from that
combine against a real Room database and times them.

The slice-8 reading, across nine runs, was that the intermediate frame always
counted the transaction *twice* — in its new budget and in the uncategorised
line — and therefore that the transient could only ever overstate, never lose
money. That was wrong, and it was wrong in the way small samples usually are:
it described which way a race happened to fall nine times, not a property of
the design.

Nothing orders the two re-queries. Whichever lands first decides the shape of
the intermediate frame:

| first flow to re-query | intermediate frame                          |
|------------------------|---------------------------------------------|
| budget spend           | counted twice — in the budget *and* uncategorised |
| uncategorised spend    | counted in neither — both lines read zero   |

Both shapes occur, in both directions. Re-measurement was 22 instrumented
runs on one machine (an arm64 Pixel 9 Pro XL emulator, API 36): 12 runs
against the original assertions, of which **6 failed** — so it was never a
CI-only failure, just one nobody had run often enough to see — then 10 runs
with every emission captured, which is where both shapes appear in both
directions. The slice-8 claim that the reverse direction (clearing a
category) showed no intermediate frame at all is likewise just a small
sample; it tears about as often as the forward direction does.

What survives re-measurement (counts are from those 22 runs; anyone
re-checking should say how many runs their own numbers came from):

- The intermediate frame is **real**, not theoretical, and appeared in
  roughly half of runs in either direction.
- The longest torn frame observed was **2.66 ms**, against a 16.7 ms 60Hz
  frame, so the state exists for a fraction of a single display refresh and
  `collectAsStateWithLifecycle` conflates a superseded value before
  composition reads it. **It cannot be seen.** That — duration, not direction
  — is the whole reason it is not worth designing around. Note that slice 8
  reported 0.24–0.68 ms from nine runs; the real spread is wider, which is
  the same sampling mistake in miniature.

So the test no longer asserts "no frame ever drops the transaction from both
sides at once." That assertion was not a guard on a property; it was a bet on
scheduling, and it fails about half the time — 6 of 12 runs — which CI
surfaced once the instrumented suite began running on every push. What it asserts instead is
order-independent:

1. **Neither side ever reports a total no query would return.** Tearing pairs
   two individually-valid snapshots; it never invents a third value. This is
   the assertion that fires if the join, the sign filter, or the write
   regresses.
2. **Each side changes value exactly once per write.** That bounds the
   transient to a single intermediate frame without pinning which of the two
   shapes it is, and catches a flow that oscillates or re-emits on unrelated
   invalidations.
3. **The pair settles on the correct totals, and the torn frame is not the
   resting state.**

The shape, count and duration of the transient are printed rather than
asserted, because pinning them would make the test a change-detector for
Room's invalidation batching.

If the "money is never missing from both sides" property is ever actually
wanted, it needs a design change rather than a stronger assertion: one query
carrying both numbers, so a single invalidation produces a single consistent
snapshot. The uncategorised total would have to ride along as a scalar
subquery rather than a joined column, and the zero-budget case — where the
totals query returns no rows and so has nowhere to hang the subquery — is the
part that needs designing, not the SQL.

**When that redesign stops being optional.** Everything above rests on one
assumption: these values are *rendered* and nothing else. Tearing is tolerable
only because the sole consumer is a composable that conflates a superseded
value before it is ever drawn, so a wrong number that exists for two
milliseconds has no effect on anything. That assumption expires the moment
anything *acts* on the pair rather than displaying it.

A budget alert is the obvious case. "Notify when spend crosses the limit"
evaluated against a torn frame fires on a number that was never true — or,
in the other direction, misses a crossing because the budget side still reads
zero. Neither self-corrects: a notification, once sent, is sent, and the
next frame settling correctly does not unsend it. The same goes for anything
that writes back, exports, or otherwise makes a torn read durable.

So: **the single-query redesign is a prerequisite for any alerting,
notification, or automation feature built on `observeMonthlyBudgets`, and
that work should be scoped as part of it rather than discovered during it.**
A half-millisecond wrong number on screen is not a bug. The same number
triggering a push notification is.

### 2.3 Period boundaries, `LocalDate`, and the timezone question

The task's framing — "a transaction dated the 31st when the user is in a
different timezone than when they entered it" — turns out to be a non-issue
*by construction*, and it's worth showing why rather than just asserting it,
because the reason is exactly the kind of thing that stops being true if
someone "simplifies" the code later.

`Transaction.date` is a `kotlinx.datetime.LocalDate` — a calendar date with
no time and no zone attached. It's set once, at entry: `TransactionFormViewModel`
resolves "today" via `clock.todayIn(TimeZone.currentSystemDefault())`, and a
manually-picked date comes back from Compose's `DatePicker` (which
represents its selection in UTC millis internally — a UI-toolkit detail —
and is deliberately converted back to a `LocalDate` via `TimeZone.UTC` at the
call site in `TransactionFormScreen.kt`, specifically to cancel that
UTC-internal representation out rather than let it leak). Once that
`LocalDate` is stored, there's no step anywhere that re-derives it through
"the device's timezone right now" — the entity column is `TEXT`, compared
lexicographically, exactly as its own doc comment says. So a transaction
dated 2026-08-31 reads as 2026-08-31 forever, regardless of what timezone
views it later, because there's no zone left in the value to reinterpret.
Flying from India to the US doesn't change any stored transaction's date; it
changes what "today" resolves to for a *new* entry, which is correct — it
genuinely is a different calendar date now.

**Where this would actually break, and the rule that keeps it from breaking:**
`TransactionFilter.kt` already states the precedent this design leans on:
"resolving what \[a relative period\] means today needs 'now', which is a
presentation-layer concern. The data layer only ever sees \[concrete\]
dates." Budget period resolution follows the identical rule. A `YearMonth`
is resolved to a concrete `[from, to]` `LocalDate` pair — `LocalDate(2026, 8,
1)` to `LocalDate(2026, 8, 31)` — by `YearMonth.dateRange()`, entirely in
`LocalDate` arithmetic, before the query runs. Month length is derived
(`first.plus(1, MONTH).minus(1, DAY)`) rather than looked up, so February in
a leap year is 29 days without leap years being special-cased anywhere. The DAO query in §4.2
never sees "this month" as a concept and never converts through `Instant` or
a device timezone to get there. The trap this avoids: computing "start of
August" by taking a UTC timestamp and localising it through
`TimeZone.currentSystemDefault()` at query time, which is exactly the kind
of thing that's correct in whatever timezone it was written and tested in
and wrong for a user one zone over — the CSV design's date-ambiguity section
is a different bug with the same underlying lesson (don't let a
representation carry an implicit assumption that only holds sometimes).

### 2.4 Carry-over

**No.** A budget resets to its own `limitMinor` every month; underspending
in July has no effect on August's Food budget. Carry-over means "limit"
stops being a stored number and becomes a computed one (base limit ±
whatever rolled in from the month before), which has to handle the first
month it's ever turned on (nothing to roll over from), a month where the
category had no budget at all (does that break the chain, or count as
zero?), and editing a past month's limit after later months have already
rolled numbers forward from it. None of that is impossible; all of it is
real design work this milestone doesn't need. That — not reversal cost — is
why the simple version ships.

The reversal asymmetry is still worth naming, because it tells whoever adds
carry-over later what to watch for, and it runs the opposite way to the
usual intuition. **Adding rollover later is the retroactive direction:** the
data to reconstruct a rollover chain all the way back already exists (every
past month has a stored limit and a computable spend), so switching it on
would silently restate what every past month's effective limit *was* —
January's underspend suddenly meaning something it did not mean when
January happened. **Removing rollover later is merely forward-looking:** it
stops compounding from the month it's turned off, and nothing already
displayed changes meaning. So a future carry-over feature must be scoped to
start from the month it is enabled rather than reconstructing history —
which defuses the retroactive problem entirely, and is the one thing about
this decision worth writing down now. See D4.

### 2.5 What the user sees when over budget

A visual state on the budget's own row (a colour change, "₹340 over budget")
computed live from the same query as everything else on the screen — there's
no separate "check if any budget is exceeded" step, it falls out of rendering
`BudgetProgress.status` for whatever's already being displayed.

**Three states, not two — a correction from reviewing the slice 8
screenshots.** Under and over were never the hard part. The state that needed
its own signal is *at or approaching* the limit: ₹0.00 left rendered in the
same colour as ₹3,700.00 left, which is a warning the user receives only
after it has stopped being actionable. `BudgetStatus` is therefore
`UNDER`/`NEAR_LIMIT`/`OVER`, with the warning band starting at 90% —
₹0 left and ₹50 left are the same practical situation, and a signal keyed on
exact equality would almost never fire, since spend rarely lands on a round
limit. Exactly at the limit also gets its own copy ("Limit reached"), because
"₹0.00 left" is accurate and reads like nothing happened.

The warning colour cannot come from `MaterialTheme.colorScheme`. Dynamic
colour derives every slot from the user's wallpaper, so `tertiary` and
`secondary` are whatever hue that produces — on a blue wallpaper they read as
another shade of the same blue as `primary`, which is exactly the distinction
the colour exists to make. It lives in a `SemanticColors` CompositionLocal
provided by `BahiTheme` instead, the same standing `error` already has.

**The progress bar does not render overflow by length.** `fractionOfLimit`
stays unclamped so the model loses nothing, but `LinearProgressIndicator`
clamps regardless; colour and the line beneath carry the over-budget state,
which reads better than a bar pinned at full. At `progress = 0f` Material3
1.3.2 draws the track and its stop indicator and no fill — the correct
"nothing spent" rendering, and unambiguous now that a zero-limit row can no
longer sit beside it (§2.1).

**No notifications.** Nothing in the app currently uses
`NotificationManager` or a `CoroutineWorker` — there's no notification
infrastructure to extend, only a WorkManager dependency sitting unused in
the version catalog. A budget notification needs a scheduled background
check, a notification channel, and a permission prompt on API 33+, none of
which exist yet and none of which this milestone's task list asks for. This
is the same call M2 made about WorkManager for CSV import, for the same
reason: the unattended-background-work case that would justify it isn't
here yet. Over-budget stays a pull (open the app, see the state), not a
push, consistent with every other piece of state in the app today.

---

## 3. Interaction: a rule reclassifying a transaction

One clarification on the task's own framing first: reclassifying a
transaction from Food to Groceries moves it between **two budgets in the
same month** (Food-August and Groceries-August), not between two different
months — a rule changes `categoryId`, never `date`. Read "budgets for both
\[categories in that\] month shift" — that's the case this section answers.

**It's recalculated live, and this falls directly out of §4.2's "compute by
query" decision rather than needing any explicit recompute step of its own.**
`BudgetDao.observeBudgetsWithSpend` returns a `Flow`; Room's Flow
invalidation tracks the tables the underlying query touches, which include
`transactions`. The moment `applyRuleCategory` updates a row's `category_id`
— whether that's from an import-time rule run, an on-demand recategorise, or
a user's own edit — every observer of that query re-runs and re-emits.
Food-August's spend drops by the transaction's amount; Groceries-August's
rises by the same amount; both budget rows on the currently-open Budgets
screen update in place, with no toast, no manual refresh, no reconciliation
step.

This is the strongest argument for query-based totals over a stored/cached
aggregate (which §4.2 was already going to recommend on the M2 precedent
alone): a cached total needs an explicit invalidation at *every* place
`category_id` can change — the rule engine, the on-demand recategorise
action, and a user's manual edit are three separate call sites today, and a
fourth (sync writing a remote category change) is coming in M4. Missing any
one of them means a budget silently shows a stale number until something
else forces a refresh. A query has no call sites to miss, because there's
nothing to invalidate.

---

## 4. Data layer

### 4.1 New tables

Both new tables carry the same sync-bookkeeping columns
`TransactionEntity` already has (`local_revision`, `remote_revision`,
`pending_operation`, `deleted_at`) even though `:core:sync` is still
interfaces-only. That's deliberate, not scope creep: CLAUDE.md's soft-delete
rule doesn't carve out an exception for "not synced yet," and retrofitting
these columns onto a table with real user data already in it later is
strictly more migration work than including them now, on tables that don't
exist yet. This is the same logic 11.1 of the CSV design used for building
`importBatchId` early — the cost of having it and going lightly used is low;
the cost of not having it compounds every day a row is written without it.

```
category_rules
  id                 TEXT PK
  category_id        TEXT NOT NULL  REFERENCES categories(id) ON DELETE CASCADE
  merchant_contains  TEXT NOT NULL
  priority           INTEGER NOT NULL
  created_at         INTEGER NOT NULL
  updated_at         INTEGER NOT NULL
  local_revision     INTEGER NOT NULL DEFAULT 1
  remote_revision    INTEGER
  pending_operation  TEXT
  deleted_at         INTEGER
  INDEX(category_id)

budgets
  id                 TEXT PK
  category_id        TEXT NOT NULL  REFERENCES categories(id) ON DELETE CASCADE
  year_month         TEXT NOT NULL   -- "2026-08", a YearMonth (§2.1)
  limit_minor        INTEGER NOT NULL
  currency_code      TEXT NOT NULL
  created_at         INTEGER NOT NULL
  updated_at         INTEGER NOT NULL
  local_revision     INTEGER NOT NULL DEFAULT 1
  remote_revision    INTEGER
  pending_operation  TEXT
  deleted_at         INTEGER
  INDEX(category_id)
  INDEX(year_month)
```

`ON DELETE CASCADE` on both, deliberately different from `TransactionEntity`'s
`ON DELETE SET_NULL` for the same foreign key. A transaction should survive
its category being deleted (it falls back to uncategorised, which is the
whole point of `SET_NULL` there) — but a rule or a budget *is* its category
relationship; a "Food" rule with no Food category left to point at, or a
budget for a category that no longer exists, isn't a degraded version of the
row, it's a meaningless one. Cascading avoids a class of orphaned row that
would otherwise need its own cleanup pass.

**No `UNIQUE(category_id, year_month)` constraint on `budgets`, even though
"one budget per category per month" is a real invariant** (§2.1). A second
near-miss worth showing, not just the one in §2.1: a plain unique index
would reject re-creating a budget for a category+month that already has a
*soft-deleted* row occupying that key — the tombstone doesn't stop being a
row just because the user can't see it, and `deleted_at` isn't part of the
index. SQLite can express a partial unique index (`WHERE deleted_at IS
NULL`), but Room's `@Entity(indices = ...)` annotation doesn't support a
partial index, so declaring one means hand-writing it outside the
annotation-driven schema, which Room's own exported-schema validation isn't
built to check consistently. Simpler and consistent with existing precedent
instead: the invariant lives in the repository, the same place
`OfflineFirstCategoryRepository.upsert` already keeps its own invariant (the
comment on why `upsert` reads the existing row before writing, so a caller
can't launder a system category into a deletable one by copying it). A
budget "upsert" looks up the non-deleted row for `(categoryId, month)`
first; if one exists, it updates that row and keeps its existing `id`,
discarding whatever id the caller passed; if not, it inserts a new one. So
`Budget.id` is not what identifies the row on the way in — a caller editing
a budget needn't have loaded it first, and a caller creating one cannot end
up with two. The repository owns that decision the same way `upsertAll` for
categories does.

The `year_month` column stays `TEXT`; the domain type is `YearMonth` (§2.1)
and the mapping is one `toString()`/`parse` pair in `BudgetMappers.kt`. That
parse is the boundary where a malformed month would surface — which is the
point of the type, since nothing downstream of it can then be handed a month
that no query will ever match.

**The same trap is waiting for `category_rules`, and it is worth recording
here rather than being rediscovered.** Rules have no natural key today —
two rules may legitimately share a `merchant_contains` string pointing at
different categories, and §1.5 resolves that by priority rather than by
forbidding it. But the moment anyone decides they *should* be unique (say,
"one rule per merchant string," to stop a user creating a rule that can
never win), the soft-delete interaction above applies unchanged: a `UNIQUE`
index over that key would be satisfied by a tombstoned row the user cannot
see and cannot delete again, so re-creating a rule they previously deleted
would fail with a constraint violation that has no visible cause. Any
uniqueness rule added to either table must either live in the repository
(the pattern above) or be a partial index excluding tombstones — which Room's
`@Entity(indices = ...)` cannot declare. This is a general consequence of
soft deletes, not a fact about budgets; it will apply to every table this
app adds while CLAUDE.md rule 7 stands.

### 4.2 Budget totals: computed by query, per the M2 precedent

```sql
SELECT b.*, COALESCE(SUM(-t.amount_minor), 0) AS spent_minor
FROM budgets b
LEFT JOIN transactions t
  ON t.category_id = b.category_id
 AND t.deleted_at IS NULL
 AND t.amount_minor < 0
 AND t.date BETWEEN :from AND :to
WHERE b.deleted_at IS NULL AND b.year_month = :yearMonth
GROUP BY b.id
```

`:from`/`:to` are resolved outside the DAO (§2.3) even though `year_month`
looks redundant with them — the join has to bound `transactions.date`
somehow, and `transactions` has no `year_month` column of its own to compare
against; `WHERE b.year_month = :yearMonth` just selects which budgets are in
play, `BETWEEN :from AND :to` is what actually scopes the sum. `LEFT JOIN`,
not `INNER`, so a budget with zero matching transactions this month still
shows a row with `spent_minor = 0` rather than vanishing from the result —
the same reason `observeFiltered`'s category/date conditions are written as
`OR`-with-a-flag rather than conditionally omitted.

No in-memory aggregation anywhere in this design. The M2 doc's framing —
"query, unless there's a reason" — applies here with an even stronger reason
than M2 had: §3 already depends on the query being the source of truth for
live recalculation to fall out for free.

### 4.3 The rule-matching engine is a pure function, not a DB query

Unlike budget totals, "which rule matches this transaction" isn't something
SQL should do — `merchant_contains` substring matching over a small,
in-memory rule set (dozens of rows, not thousands) is simpler and more
testable as plain Kotlin than as a `LIKE '%...%'` query with
priority-ordered `CASE` logic:

```kotlin
// :core:data, no Room/Android types involved
internal fun applyRules(
    rules: List<CategoryRule>,
    candidates: List<Transaction>,
): Map<String, String>   // transaction id -> category id, changes only
```

Every caller (import, on-demand, apply-this-rule) funnels through this one
function and then through `applyRuleCategory` — one matching
implementation, one write path, not three of each. **`AutoCategoriser`, a
small class in `:core:data`, is what makes that literally true rather than a
convention:**

```kotlin
class AutoCategoriser(categoryRuleRepository, transactionRepository) {
    /** Returns how many rows actually changed -- not how many matched. */
    suspend fun categorise(candidates: List<Transaction>): Int
}
```

It reads the rules, calls `applyRules`, and hands the result to
`TransactionRepository.applyRuleCategories`. The alternative was making
`applyRules` public — `:core:importer` can't call an `internal` function in
`:core:data` — and letting each of the three triggers do the two steps
itself. That works right up until one of them doesn't: matching is harmless
on its own, so a caller that matched and then wrote through `update()`
instead of `applyRuleCategory` would look perfectly reasonable in review and
would silently overwrite categories the user had locked. Keeping `applyRules`
internal means the pairing isn't something a caller can get wrong, because
matching without the guarded write isn't reachable from outside the module
at all.

What `AutoCategoriser` deliberately does *not* do is choose the candidates.
Each trigger has a genuinely different answer — an import means the rows
that import actually inserted (§3 below), the on-demand action means every
uncategorised row — and folding that in would hide the one decision each
caller has to make for itself.

Four things `applyRules` does that the obvious one-liner version doesn't,
each of which is a case that would otherwise be wrong rather than merely
unhandled:

- **It re-checks `categoryLockedByUser` itself.** An earlier draft of this
  section said `candidates` is assumed pre-filtered by the caller and that
  this function doesn't re-check — while §5 simultaneously specified a test
  asserting a locked transaction passed in is *not* returned. Those can't
  both hold. Resolved in favour of checking: it's one `filterNot`, it makes
  the function safe to call with any list rather than only one somebody
  remembered to filter, and it gives the constraint three independent
  guards (caller's query, here, and `applyRuleCategory`'s `WHERE`) rather
  than two.
- **It sorts by `(priority, id)`, not by `priority` alone.** §1.5 promises a
  total order; sorting on priority alone leaves ties resolved by whatever
  order the caller supplied, which is deterministic only by accident.
- **It drops rules whose `merchantContains` is blank.** `contains("")` is
  true for every string, so a single empty rule reaching this function
  recategorises the user's entire history in one pass. Rule creation should
  reject it too; the damage is unbounded enough to be worth stopping twice.
- **It omits transactions already in the category the rule would assign.**
  Those are no-op writes, and including them inflates the "this will
  recategorise 14 transactions" count in §1.6 into a number that disagrees
  with what actually happens.

### 4.4 Module placement

- `:core:model` — `CategoryRule`, `Budget`, `BudgetProgress`, `MonthlyBudgets`,
  `RuleApplicationPreview`
  (domain models, pure Kotlin, same tier as `Category`/`TransactionFilter`).
  `MonthlyBudgets` wasn't in the original list; §2.2 gained the paragraph
  below that explains why it exists.
- `:core:database` — `CategoryRuleEntity`, `BudgetEntity`, `CategoryRuleDao`,
  `BudgetDao`, `Migrations.MIGRATION_2_3`, schema JSON, `MigrationTest`
  additions.
- `:core:data` — `CategoryRuleRepository`/`OfflineFirstCategoryRuleRepository`,
  `BudgetRepository`/`OfflineFirstBudgetRepository`, `applyRules` (§4.3),
  `CategoryRuleMappers.kt`, `BudgetMappers.kt` — same shape as the existing
  `Category`/`Transaction` pairs.
- `:core:importer` — `DefaultCsvImporter.import()` gains one call to the
  engine after `importAll` succeeds. No new module edge: it already depends
  on `:core:data`.
- `:feature:budgets` — both screens (budgets, and rule management), see
  decision D5 for why this doesn't get a new module the way CSV import did.

---

## 5. Testing strategy

- **`applyRules`** (§4.3): pure-Kotlin unit tests in `:core:data/src/test` —
  no match, substring buried in export noise, case-insensitivity in both
  directions, priority order, tie-break by id with the rules supplied in
  reverse order, a merchant-populated transaction preferred over
  description, a blank rule matching nothing rather than everything, and a
  transaction already in the rule's category not being reported as a
  change. Plus, explicitly, since it's the constraint the whole feature
  exists to protect: a locked transaction passed in *unfiltered* does not
  appear in the result. Note what that last one does and doesn't prove — it
  tests this function's own guard, not the caller's query. Layer 1
  regressing is only caught by a test of the caller; layer 2 by the DAO test
  below. Three guards need three tests, and no unit test of a pure function
  can stand in for the other two.
- **`applyRuleCategory`** (§1.4): an `androidTest` against a real in-memory
  Room DB, mirroring `TransactionDaoTest`'s existing style — asserts a
  locked row is untouched by a direct DAO call even when passed its id
  explicitly, which is the test that catches layer 2 regressing
  independently of layer 1.
- **`BudgetDao.observeBudgetsWithSpend`** (§4.2): `androidTest` — a budget
  with no transactions reads 0 (not absent), income transactions don't
  count, a transaction dated the last day of the month counts and the first
  day of the next month doesn't, a soft-deleted transaction doesn't count.
- **Budget upsert-by-natural-key** (§4.1): a repository test against a fake
  DAO asserting a second `upsert` for the same `(categoryId, yearMonth)`
  updates the existing row's id rather than creating a second row, and that
  a soft-deleted budget for that key doesn't block a new one.
- **`MigrationTest`**: `migrate2To3_addsBudgetsAndCategoryRulesTables` —
  seed a v2 database, run `MIGRATION_2_3`, assert both tables exist with the
  right columns and that a foreign-key cascade actually deletes a rule when
  its category is deleted (SQLite requires `PRAGMA foreign_keys = ON` for
  this to be observable at all — worth asserting the pragma is in effect,
  not just assuming it, since Room's default differs by version).

---

## 6. Proposed M3 slices

Each compiles and passes `checkModuleBoundaries` on its own; UI slices (7, 8)
depend only on earlier data slices, not on each other.

1. **Schema**: `CategoryRuleEntity`, `BudgetEntity`, `MIGRATION_2_3`, schema
   JSON, `MigrationTest` cases (§4.1, §5). No DAOs beyond what Room generates
   from `@Entity` yet — this slice is purely "the tables exist and migrate
   cleanly."
2. **DAOs, repositories, domain models, mappers** for both budgets and rules
   — CRUD only, including the upsert-by-natural-key behaviour for budgets
   (§4.1). No matching logic, no totals query yet.
3. **`applyRules`** (§4.3): the pure matching function and its unit tests.
   No DB write path yet — this is the slice most worth independent review,
   the same way column-role inference was the CSV design's hardest slice.
4. **`applyRuleCategory`** (§1.4) and the repository method that calls it,
   with the `androidTest` proving the lock guard holds even when called
   directly. Wires slice 3's output to a real write for the first time.
5. **Apply-at-import-time**: `DefaultCsvImporter.import()` calls slices 3+4
   after `importAll`. Existing importer fixtures gain assertions.
6. **Budget totals**: `observeBudgetsWithSpend`, the uncategorised-spend
   query, `BudgetProgress`/`MonthlyBudgets`, and their tests (§4.2, §2.2,
   §2.3's month-boundary cases).
7. **Rules UI**: list/create/edit/delete/reorder screens in `:feature:budgets`
   (stateful/stateless pair, sealed `RulesUiState`, test tags), plus the
   "apply to existing transactions" preview-and-confirm flow from §1.6 and
   the "Recategorise uncategorised transactions" action from §1.3. Also the
   data layer both flows need — `RuleApplicationPreview`, the candidate
   queries that are §1.4's layer 1, `countLockedMatches`, and rule
   reordering — plus the blank-needle guards §1.1 called for and slice 3
   left unbuilt.
8. **Budgets UI**: budget list/create/edit screens, progress display and
   over-budget state (§2.5), the Uncategorised line (§2.2) — replaces the
   stub composable. Also `YearMonth.plusMonths` for month navigation,
   `SystemCategoryIds` so the picker can exclude Income and Transfers without
   duplicating ids across a module boundary, and the transient measurement
   in §2.2.

---

## 7. Decisions

### D1 — Rule matching: substring only, or add amount range now? — resolved 2026-08-24: substring only

- **Options:** (a) merchant substring only, as proposed in §1.1. (b)
  substring plus an optional amount-range condition, ANDed. (c) full regex.
- **Recommendation: (a).**
- **If wrong:** cheap to fix. Adding nullable `minAmountMinor`/
  `maxAmountMinor` columns to `category_rules` later is an additive
  migration with no data loss and no change to existing rules' behaviour
  (both null = today's substring-only semantics). Choosing (c) instead and
  regretting it is the expensive direction — it would mean migrating
  user-authored regexes, not just a schema.

### D2 — Starter rule set: none, seeded, or suggested? — resolved 2026-08-24: none, user-created only

- **Options:** (a) none — user-created only, as proposed in §1.2. (b) seed a
  curated Indian-merchant set, active by default. (c) build the
  "suggest a rule after repeated manual categorisation" version described
  in §1.2, inactive until the user accepts one.
- **Recommendation: (a)** for M3; **(c)** is the version worth building, just
  not in this milestone — it needs its own tracking and doesn't block
  anything else here.
- **If wrong:** picking (a) now and wanting (b) or (c) later costs a new
  feature, not a migration — no schema decision here forecloses either.
  Picking (b) now and it going badly (a wrong seeded rule silently
  miscategorising real transactions) is much harder to walk back, because by
  then it's not a design problem, it's already-wrong data sitting in a
  user's ledger.

### D3 — "Overall" (non-category) budgets: support in v1? — resolved 2026-08-24: no, per-category only

- **Options:** (a) no — per-category only, as proposed in §2.1. (b) yes, via
  a real sentinel category row (like `"uncategorised"`), non-null
  `categoryId`. (c) yes, via nullable `categoryId`, accepting that
  uniqueness has to be enforced in application code instead of a DB
  constraint (the NULL-uniqueness issue in §2.1 doesn't disqualify this, it
  just means the DB can't help).
- **Recommendation: (a).**
- **If wrong:** adding (b) later is a small additive migration (one seeded
  category-like row) plus a repository check, not a rewrite — the schema in
  §4.1 already doesn't preclude it.

### D4 — Carry-over: does underspending roll forward? — resolved 2026-08-24: no rollover

- **Options:** (a) no rollover, as proposed in §2.4. (b) unspent amount
  automatically adds to next month's limit. (c) rollover is opt-in per
  budget (a boolean on the row).
- **Recommendation: (a)** — resolved 2026-08-24. Not because it's the easier
  decision to reverse, but because carry-over is materially more design
  (§2.4's three unresolved cases) for something this milestone doesn't need.
  The simpler thing ships on its own merits.
- **If wrong:** the direction of the risk is the opposite of the intuitive
  one, and §2.4 spells it out: **adding** rollover later is the retroactive
  change — every past month already has the stored limit and computable
  spend needed to reconstruct a chain, so switching it on could silently
  restate what historical months meant. **Removing** it later would only
  stop a forward-looking behaviour. The mitigation is a constraint on the
  future feature, not on this decision: any carry-over added later must
  start from the month it is enabled and must not reconstruct history.

### D5 — Module placement for rule management: inside `:feature:budgets`, or its own module? — resolved 2026-08-24: inside `:feature:budgets`

- **Options:** (a) both budgets and rule-management screens live in
  `:feature:budgets`, as proposed in §4.4 — it's the only module this
  milestone already reserved. (b) a new `:feature:categorization` module,
  mirroring the `:feature:import` precedent from csv-import-design.md §11.2.
- **Recommendation: (a).** The reasoning that justified a dedicated module
  for CSV import doesn't transfer: the README names CSV import and sync
  conflict resolution as the two hard problems this repo highlights, and a
  diagram node exists to make that claim visible. Auto-categorisation here is
  a substring match with a priority order — real, but not README-headline
  material — so there's no equivalent claim that needs a node to point at.
- **If wrong:** same "mechanical, bounded" cost §11.2 already names for its
  own reversed case — extracting a package into its own module later is
  build-logic wiring and a `moduleGraph` regen, not a rewrite.

### D6 — `Transaction.merchant`: populate it, or drop it?

- **The problem:** as §1.1 sets out, `merchant` is written by nothing and
  read by nothing. It is a column carried by every row, in a schema that now
  has real migrations run against it, doing no work.
- **Options:** (a) keep it as-is, with merchant extraction from
  `description` named as the slice that would justify it — the one change
  that would most improve rule matching quality. (b) build that extraction
  now, as part of M3. (c) drop the column and the model field.
- **Recommendation: (a)**, with a deadline rather than an open-ended keep:
  if extraction hasn't been built by the end of M4, drop it then. (b) is
  real normalisation work with its own silently-wrong failure modes and
  would compete with the two features M3 is actually about.
- **If wrong:** (c) is the direction that is more expensive than it looks
  and gets more expensive with time, which is why the deadline matters.
  `minSdk` is 26; `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35+ (Android
  14 / API 34), so removal on API 26 is the 12-step table rebuild — every
  row copied through a new table. Cheap to fold into a rebuild M4's sync
  work needs anyway; not worth a dedicated migration on its own.
