# CSV import — design

M2. Covers column-mapping inference, the preview/correction flow, de-duplication,
failure handling, threading, and the test plan. No code in this document is meant
to be copy-pasted; it's there to make the reasoning checkable.

Related reading: `core/importer/.../CsvImporter.kt` (the M0 placeholder interfaces),
`Money.parse` (`core/model/.../Money.kt`), `contentHashOf` and `TransactionDao.importBatch`
(`core/data`, `core/database`).

---

## 1. What the M0 interfaces get wrong

The placeholder shape is a reasonable sketch but under-specifies three things that
turn out to be load-bearing:

- **`headerRowIndex: Int` can't represent "no header row."** Some exports start
  straight at row 0. It also conflates "where the header is" with "where data
  starts," which breaks the moment there's a preamble (account name, statement
  period, a blank line) above the header.
- **`amountColumn` vs `debitColumn`/`creditColumn` doesn't cover sign
  convention.** A single amount column can have debits negative (common),
  debits positive, or be unsigned with a separate `Dr`/`Cr` marker column. The
  current shape assumes "amount column implies sign-is-the-signal," which is
  true often enough to look right in a demo and wrong on a real statement.
- **`confidence: Float` on `ImportPreview` is a single number for a
  multi-part decision.** Date column, amount interpretation, and description
  column are inferred independently and can each be wrong independently. A
  single float can't tell the correction UI *which* part to ask about, so
  everything ends up being asked about, which defeats the point of inferring
  anything.

Proposed replacement shape (fields, not final Kotlin — see §7 for where this
actually lands):

```
ColumnMapping(
    headerRowIndex: Int?,       // null = no header row
    firstDataRowIndex: Int,     // handles preamble even when a header exists
    dateColumn: Int,
    dateFormat: String,
    descriptionColumn: Int,
    amountColumn: Int?,
    amountSign: AmountSign?,    // NEGATIVE_IS_DEBIT | POSITIVE_IS_DEBIT | SIGN_COLUMN
    signColumn: Int?,           // only set when amountSign == SIGN_COLUMN
    debitColumn: Int?,
    creditColumn: Int?,
)

ImportPreview(
    mapping: ColumnMapping?,
    uncertainFields: Set<MappingField>,   // empty = confident, no prompts needed
    sampleRows: List<PreviewRow>,         // already mapped to date/description/amount, not raw cells
    unmappedColumns: List<Int>,           // e.g. a detected running-balance column, shown but not used
    warnings: List<String>,
)
```

`uncertainFields` is the actual fix for the confidence problem: it names which
roles need a human, so the correction UI can leave everything else alone.
`sampleRows` being pre-mapped (not raw cell grids) matters for §3 — the user
should be reviewing transactions, not columns.

`FailedRow` and `ImportResult.duplicatesSkipped`/`failedRows` are fine as-is.

---

## 2. The inference problem

The approach is a pipeline of independent, cheap signals, each producing either
a confident answer or an explicit "don't know." Nothing in here does anything
statistical or fuzzy-matched; every signal is answerable by parsing a cell with
`Money.parse` or a date parser and checking a rate over a sample of rows (first
~50 data rows is plenty — bank exports don't change format mid-file, and this
caps preview latency on a large file).

**Finding the data.** Compute the modal column count across all rows (preamble
rows and the header often have a different cell count than data rows, so the
mode reliably identifies the data rows' shape). Walk forward from row 0 for the
first row matching the mode's column count that *also* has at least one cell
parsing as a date and one other cell parsing as money — that's
`firstDataRowIndex`. If the row directly above it also matches the modal column
count but its cells parse as neither dates nor amounts, that's the header
(and its text becomes a signal for role assignment, see below); otherwise there
is no header. If nothing matches within, say, the first 50 rows, inference gives
up entirely rather than guessing — see §3 for what the user sees then.

**Column roles**, evaluated per column over the sampled data rows:

- *Date column*: whichever column has the highest parse rate against a small
  set of known formats (`dd/MM/yyyy`, `MM/dd/yyyy`, `yyyy-MM-dd`, `dd-MMM-yyyy`,
  and their common separator variants). Ties or a low top rate go into
  `uncertainFields`.
- *Amount-shaped columns*: any column with a high `Money.parse` success rate is
  a candidate. If there are two or three such columns, they need to be told
  apart:
  - **Running balance** is detected by relation, not appearance: for a
    candidate amount column, check whether `balance[i] - balance[i-1] ≈
    amount[i]` (or the negation) holds for most consecutive rows against
    another candidate. A column that satisfies this against a real amount
    column is a balance column — pull it out of the candidate set and surface
    it in `unmappedColumns` rather than mapping it. This is the direct answer
    to "trailing balance columns that look like amount columns": the
    per-column parse rate genuinely can't tell them apart, but the
    row-to-row relationship can.
  - **Debit/credit pair** vs **single signed column**: if exactly two
    amount-shaped columns remain and, per row, exactly one of the two is
    populated, that's a debit/credit pair. One remaining amount-shaped column
    is a single signed (or sign-column-marked) amount.
  - Header text, when available, resolves *which* of a debit/credit pair is
    which ("Withdrawal"/"Deposit", "Debit"/"Credit", "Dr"/"Cr"), and confirms
    sign convention for a single column. Without a header, debit/credit
    left-to-right order is a weak prior — flag it in `uncertainFields` rather
    than trust it silently, since getting debit/credit backwards silently
    flips the sign of every transaction in the file.
- *Description column*: whatever's left after date/amount/balance, preferring
  header hints (`narration`, `particulars`, `description`, `details`) and,
  failing that, the column with the highest average string length / token
  count (a description column is high-cardinality free text; a reference
  number or code column is not).

**Date format ambiguity** is the one case that deserves special handling rather
than folding into the generic "low confidence" bucket, because it's silent and
total when it goes wrong: `03/04/2026` parses under both `dd/MM/yyyy` and
`MM/dd/yyyy`, produces a *valid* date either way, and a wrong choice doesn't
error — it just quietly shuffles every date in the import by however far
day and month diverge. The rule: scan every value in the date column. If any
value has a component over 12 in the day position, the format must be
day-first; if any has one over 12 in the month position, it must be
month-first. If the file contains no disambiguating value at all — genuinely
possible for a statement covering less than 13 days of a month — inference
cannot decide, full stop, and this always goes into `uncertainFields` even
though a plausible guess exists. This is a case where I'd rather be visibly
unconfident than quietly wrong 50% of the time. See §3 for how the picker
surfaces it (side-by-side resolved dates, not a "day-first / month-first"
dropdown).

**Quoted fields / encoding.** RFC 4180 quoting (`"a, b"`, doubled `""` for an
embedded quote, and quoted fields that themselves contain the delimiter) is a
tokenizer problem, not an inference problem — it has to be handled correctly
before any of the above runs, or a comma inside a merchant name silently
shifts every column after it. Two variants that are easy to skip and worth
naming explicitly: a quoted field containing a literal newline (the tokenizer
has to be record-aware, not line-aware, or it splits one row into two), and a
leading UTF-8 BOM (`﻿`), which Excel writes on save-as-CSV and which,
left unstripped, silently breaks whatever parses the first cell of the first
row. Both are in the fixture set (§8). This is the one place I want to flag:
the module currently has no CSV parser at all, hand-rolled or otherwise (see
§7 for the dependency question).

---

## 3. Preview and correction flow

The preview shows **mapped transactions**, not a column-index picker, because
that's what the user can actually judge correctness of. A raw grid with
"column 3 = ?" dropdowns asks the user to do the inference the app should be
doing.

- **Confident case** (`uncertainFields` empty): a short summary — "18
  transactions, 15 Jan – 3 Feb, dates as DD/MM/YYYY" — over the mapped sample
  rows, and a single "Import" action. Zero taps beyond that if the guess is
  right, which it should be for any conventionally-formatted export.
- **Uncertain case**: the preview still renders using the best-guess mapping
  (better to show *something* plausible than block on a picker), but each
  uncertain field gets an inline affordance on the relevant part of the sample
  row — e.g. tapping a date opens a sheet showing the ambiguous value resolved
  both ways ("3 April 2026" vs "4 March 2026," pick one, applies to the whole
  column) rather than asking the user to reason about format strings. A
  debit/credit assignment that couldn't be confirmed gets a similar "which of
  these is money out?" toggle over two real sample amounts.
- **Total inference failure** (couldn't find data rows, or found data rows but
  couldn't identify a date or amount column at all): falls back to a raw grid
  with column-role assignment by hand. This is the floor, not the common case,
  and it's the same information the confident path uses internally, just
  exposed.
- **Unmapped/failed rows** are shown as a count with a way to expand
  ("3 rows couldn't be read"), not buried in the results after import.

Correcting a field re-derives the preview from the already-parsed rows in
memory — no re-read of the file, so corrections are instant.

---

## 4. De-duplication

`contentHashOf` (accountId, date, amount minor units, upper-trimmed
description) and `TransactionDao.importBatch` already exist and already run
for every import via `TransactionRepository.importAll`. Worth stating plainly
what it does and doesn't handle before proposing a change to it.

**Overlapping statement re-import** — the case it's built for — works: a row
re-exported with identical date/amount/description hashes the same, and
`findExistingHashes` finds it already in the table.

**Two genuinely identical transactions on the same day** — same coffee shop,
same amount, twice — is where the current implementation has a real bug, not
a hypothetical one. `importBatch` checks hash *presence*, not hash *count*:

```
val existing = findExistingHashes(transactions.map { it.contentHash }).toSet()
val fresh = transactions.filterNot { it.contentHash in existing }
```

Presence-based filtering is fine the first time a batch is imported (nothing
exists yet, both coffees get inserted). It breaks the moment a *second*,
overlapping import contains that same pair: the DB now has one row with hash
`H`, `existing` contains `H`, and `filterNot { H in existing }` drops **both**
of the incoming batch's `H`-rows — including one that's a legitimate new
transaction sharing the tuple, not a re-import of the same row. The failure
mode is silent data loss on a case the interfaces list explicitly calls out,
which is why I'm treating it as part of this design rather than a follow-up.

Proposed fix: make the check count-aware. `findExistingHashes` returns a count
per hash instead of a set; `importBatch` walks the incoming rows, and for each
hash keeps the first `(incoming count for that hash − existing count for that
hash)` occurrences, dropping the rest. Two coffees in a fresh import, zero
existing → both kept. Re-importing that same file, two incoming, two existing →
both dropped, correctly. A third coffee added in a later, overlapping
re-export, two incoming become recognized as existing and the third is new →
kept. This is a query-shape and logic change in `TransactionDao`/
`OfflineFirstTransactionRepository`, not a schema change — `content_hash`
already exists and isn't a unique constraint, so no migration is needed.

This relies on same-tuple rows appearing in a stable relative order across
re-exports of overlapping statement periods, which is true for every bank
export I've seen (chronological, ties broken by an internal sequence number)
but isn't guaranteed by anything. Worth a one-line comment at the call site
when this gets built; not worth engineering around further at this scale.

---

## 5. Failure handling

Row-level failures (bad column count, unparseable date or amount) are caught
per-row during mapping, not file-wide: a 400-row file with 3 bad rows produces
397 `Transaction`s and 3 `FailedRow`s, and the 397 are what gets committed.
This is already the shape `ImportResult` implies; the design just confirms
that failures are collected rather than thrown.

The commit itself stays inside `TransactionDao.importBatch`'s single `@Room
Transaction` — atomic at the DB level, so a crash mid-import can't leave a
half-applied batch; worst case is the whole batch didn't happen and the user
retries. That's a property the current implementation already has and this
design doesn't need to touch.

**Undo** is the one gap: nothing currently identifies which rows came from
which import run, so "undo this import" isn't expressible — only the existing
per-row swipe-to-delete (built in M1) is available, and that means undoing a
397-row mistaken import is 397 taps. Fixing this needs a nullable
`importBatchId` on `Transaction`/`TransactionEntity`, set only for
`CSV_IMPORT` rows, plus an `undoImport(batchId)` repository method that
soft-deletes everything with that id — reusing the existing tombstone
machinery, so it stays sync-safe for free. This is a schema change bound by
rule 6 (migration + `MigrationTest` + exported schema JSON, same commit), and
I'm treating it as optional/separable rather than assuming it belongs in M2 —
flagged as a decision in §8.

---

## 6. Threading

Parsing and mapping run on the injected IO dispatcher via a plain suspend
function, the same pattern `OfflineFirstTransactionRepository` already uses
(`withContext(ioDispatcher) { ... }`) — no WorkManager. At "hundreds of rows"
this is milliseconds of parsing plus one Room transaction that's well under a
second; `viewModelScope` surviving configuration change and backgrounding
(the process stays alive) is enough. WorkManager earns its keep for
unattended/background work or multi-thousand-row imports; neither applies
here, and reaching for it now would be scope the milestone doesn't need — the
same reasoning `core/importer`'s doc comment already gives for keeping M2 and
M4 as interfaces-only until their milestone arrives.

The one place progress genuinely matters is the parse/preview phase, since
that's where all the per-row date/amount parsing happens and it's not wrapped
in a DB transaction — a `Flow<Int>` (rows processed) collected by the
ViewModel is cheap and real. The final commit, by contrast, is one atomic
transaction and fast at this scale, so it gets an indeterminate spinner rather
than row-level progress; chunking the commit for progress granularity would
trade away the atomicity guarantee in §5 for a UI nicety that a sub-second
operation doesn't need.

If the process is actually killed mid-import (not backgrounded — killed), the
transaction's atomicity means there's nothing to resume: it either committed
or it didn't. No explicit recovery path is needed beyond "re-run the import,"
which de-duplication (§4) makes safe.

---

## 7. Where the logic lives, and the dependency question

**No new CSV parsing dependency, with one honest caveat.** The quoting rules
that matter here are narrow — quoted fields, comma and embedded-quote
escaping, embedded newlines inside a quoted field, a leading BOM, CRLF vs LF —
and hand-rolling a small state-machine tokenizer is in keeping with how
`Money.parse` was built for this exact problem (parenthesized negatives,
mixed separators) rather than pulling in a formatting library.

What a library actually buys, and hand-rolling actually risks, is *dialect
coverage from exposure we haven't had*: Commons CSV and opencsv have been
hammered against years of exports from Excel, LibreOffice, Google Sheets and
whatever bank software produced them, including malformed-but-common cases
(unbalanced quotes, mixed quoted/unquoted cells in the same column) that
don't show up until a real file breaks them. A hand-rolled tokenizer is only
as correct as its fixture set. The failure mode isn't a crash — it's a
tokenizer that silently mis-splits a row (a comma inside an unquoted-but-
should-have-been-quoted field, say) and produces a preview that still *looks*
plausible, gets approved, and imports shifted data. That's a real argument
for the library, and I don't want to understate it.

I still lean hand-rolled, because the alternative to "our fixture set might
be incomplete" isn't "a library's dialect coverage is complete" — it's
trading one bounded risk for a dependency, and the CLAUDE.md default is to
ask first. But this is the one place in the design where I'd actually
change my recommendation if real fixture files (once we have some to test
against, even synthetic ones sourced from more banks) turn up quoting
behavior outside RFC 4180. See the decision list in §10.

The tokenizer and the inference engine (§2) are pure functions over strings —
no Room, no Android framework — even though `:core:importer` is an Android
library module (it already depends on `:core:data` for repository types and
uses Hilt for DI). That's fine as-is; I'm not proposing a module split, just
noting the inference logic itself is easy to unit-test without Robolectric
even inside an Android module.

`CsvImporter.import(csv, mapping, accountId)`'s `accountId` parameter has
nothing to attach to yet — there's no `Account` entity or picker; the app
currently hardcodes a single `DEFAULT_ACCOUNT_ID` (`TransactionFormViewModel`,
`DebugSeeder`). I'm treating that as an existing, acceptable simplification
for M2 rather than a gap to fix here: import wires to the same default
account constant, and multi-account import is out of scope until an `Account`
concept exists.

---

## 8. Testing strategy

All fixtures are hand-written synthetic CSVs (2–6 rows, fake merchants), not
real statements — the same approach `MoneyTest` already takes for parsing
edge cases. What's being tested is format *shape*, which doesn't need to be
real financial data to be representative.

Fixture cases, roughly in order of how likely each is to break something:

1. Clean signed-amount column, header present, ISO dates — the baseline.
2. Debit/credit split columns with header text (`Withdrawal Amt`/`Deposit Amt`).
3. No header row — data starts at row 0.
4. Preamble rows (account name, statement period, a blank line) before the header.
5. Genuinely ambiguous dates (every value ≤12/≤12) — asserts `uncertainFields`
   contains the date field, not a silent guess.
6. Unambiguous day-first and month-first dates (a value >12 in each position) —
   asserts the correct format is picked with no prompt.
7. `15-Aug-2026` month-name dates.
8. A trailing running-balance column — asserts it lands in `unmappedColumns`,
   not `amountColumn`.
9. Quoted description containing a delimiter and an escaped quote
   (`"Coffee, ""Downtown"" Ltd"`).
10. A handful of malformed rows mixed into an otherwise-good file (wrong
    column count, unparseable amount) — asserts partial success and the right
    `FailedRow`s, not a wholesale failure.
11. Two identical-tuple rows in one file (the duplicate-coffee case) — asserts
    both import.
12. The same file imported twice — asserts both are skipped the second time.
13. Case 11 followed by a re-import that adds a third matching row — asserts
    two are skipped and one is kept (the count-aware fix in §4).
14. CRLF line endings and a trailing blank line at EOF.
15. A quoted description field containing a literal newline — asserts the
    tokenizer is record-aware, not line-aware.
16. A file with a leading UTF-8 BOM — asserts it's stripped before the header/
    first cell is parsed.
17. European (`1.234,56`) and Indian (`12,34,567.89`) amount formats in the
    same file — `Money.parse` already covers the parsing; this is checking the
    column-role inference doesn't get confused by the formatting, not
    re-testing `Money.parse` itself.

Cases 5, 8, 10, 11 and 13 are the ones most likely to regress silently if
touched later, since each corresponds to a place where the naive
implementation produces a *plausible-looking wrong answer* rather than an
error.

---

## 9. Proposed M2 slices

Each slice compiles and passes `checkModuleBoundaries` on its own; nothing
here depends on a later slice to be reviewable.

1. **Reshape `:core:importer` interfaces** to the §1 shape. No logic — just
   the contract, so everything after this reviews against a stable interface.
2. **CSV tokenizer**: hand-rolled RFC 4180-ish parser (quoting, escaping,
   CRLF/LF, embedded newlines, BOM stripping) with its own unit tests
   (fixture cases 3, 9, 14, 15, 16 from §8). Pure function, no inference.
3. **Preamble/header detection + column role inference** (§2): the data-row
   finder, date/description/amount/debit-credit/balance role assignment, and
   confidence tracking via `uncertainFields`. This is the slice that most
   needs independent review — it's the actual hard problem.
4. **Date-format disambiguation**: the ambiguous-vs-resolvable logic and its
   tests (fixture cases 5, 6, 7). Small, but separable from #3 since it's a
   distinct sub-decision with its own failure mode.
5. **Count-aware de-duplication fix** (§4) to `TransactionDao`/
   `OfflineFirstTransactionRepository`. No schema change. Independently
   reviewable and independently valuable even before CSV import exists to
   exercise it.
6. **`CsvImporter` implementation**: wires tokenizer + inference into
   `preview()`/`import()`, calling the existing `TransactionRepository.importAll`.
7. **Preview/correction UI**: stateful/stateless screen pair, sealed
   `ImportUiState`, the correction sheets from §3. Raises the module-structure
   question in §10 below before this slice starts.
8. **Import-batch undo** (§5): `importBatchId` column, migration,
   `MigrationTest`, `undoImport`. Only if approved — see §10.

---

## 10. Decisions I'd want before building

### 10.1 Does batch undo (slice 8) belong in M2?

- **Options:** (a) build it — `importBatchId` column, migration,
  `MigrationTest`, `undoImport`, as its own slice; (b) defer it — M2 ships
  with only the existing per-row swipe-to-delete from M1.
- **Recommendation:** (a), build it. It's one nullable column and a soft-
  delete-by-batch-id query, reusing machinery that already exists — cheap
  now.
- **If we pick wrong:** Deferring and adding it later means every
  `CSV_IMPORT` row inserted before the migration lands has `importBatchId =
  null` forever — batch undo would only cover imports made *after* the
  feature ships, not the ones that motivated it. Building it now and it
  turns out unneeded costs one unused nullable column and a small migration
  — low, bounded cost either way, which is most of why I lean toward
  building it.

### 10.2 Does the import screen get its own module?

- **Options:** (a) new `:feature:import` module (16th module); (b) fold the
  screens into `:feature:transactions`.
- **Recommendation:** (a). The README already frames CSV import as one of
  the two hardest problems in the repo; a module boundary around it is the
  most legible way to show that in the architecture diagram a reviewer looks
  at first.
- **If we pick wrong:** A new module that turns out to be overkill costs
  build-logic wiring, a `moduleGraph` regen, and a README diagram update —
  annoying but mechanical. Folding it into `:feature:transactions` and
  wanting to split it out later is worse: it means extracting files across
  a module boundary after the fact, from a module that by then also owns
  list/filter/CRUD, rather than starting with the boundary already in place.
  Either mistake is recoverable; the module-split direction is cheaper to
  recover from.

### 10.3 Hand-rolled CSV tokenizer, or a parsing library?

- **Options:** (a) hand-roll a small RFC 4180-ish tokenizer, no new
  dependency; (b) take a dependency (Commons CSV or opencsv).
- **Recommendation:** (a), consistent with how `Money.parse` was hand-built
  for this exact class of problem, and CLAUDE.md's default to ask before
  adding a dependency.
- **What hand-rolling actually risks** (this is the real tradeoff, not a
  formality): a library has been battle-tested against years of real-world
  export quirks — malformed-but-common dialects, unbalanced quotes, mixed
  quoted/unquoted cells in one column — that a fresh implementation hasn't
  seen. The failure mode isn't a crash, it's worse: a tokenizer that
  silently mis-splits a row on a quirk our fixtures didn't anticipate
  produces a preview that still *looks* plausible, gets approved, and
  imports shifted data.
- **If we pick wrong:** picking (a) and hitting an uncovered dialect means a
  bad preview on a real user's file — visible before commit if the shifted
  columns produce nonsense (unparseable dates/amounts, caught by §5's
  failure handling), but not necessarily visible if the shift still happens
  to produce plausible-looking garbage. Picking (b) and it turning out
  unneeded means one more entry in `gradle/libs.versions.toml` the
  dependency list didn't need. I'd revisit this specific call the moment a
  real fixture file turns up quoting behavior outside RFC 4180 — that's the
  concrete trigger, not a vague "if it becomes a problem."
