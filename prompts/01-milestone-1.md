# Task 01 — M1: Transactions and categories

Run this **only after Task 00 is green**. Read `CLAUDE.md` first — its architecture rules are hard constraints.

Work in slices, in the order below. **Stop after each slice, run the tests, and show me what you did before starting the next one.** Do not do all four in one pass.

---

## Goal

A usable transaction tracker: add, edit, delete and categorise transactions, with a list grouped by date and a running balance. Everything works with no network. This is the state the README screenshot will show.

---

## Slice 1 — Categories in the data layer

- `CategoryRepository` interface + offline-first implementation in `:core:data`, mirroring the shape of `TransactionRepository`.
- Entity↔domain mapping in a `CategoryMappers.kt`, following `TransactionMappers.kt`.
- Seed ~12 system categories (Food, Transport, Rent, Utilities, Groceries, Health, Shopping, Entertainment, Transfers, Income, Fees, Uncategorised) on first launch, each with a colour and an icon key. Seeding must be idempotent — reinstall-safe, and never overwrites a user's edit to a system category.
- Unit tests: seeding runs once, user categories can be deleted, system categories cannot.

## Slice 2 — Transaction list

Replace the placeholder list in `feature/transactions`.

- Group by date with sticky date headers; newest first.
- Each row: description, category chip, `MoneyText` (already in `:core:ui`).
- A running total for the visible period in the top app bar.
- Handle all four UI states properly — `Empty` needs a real empty state with a call to action, not just text.
- Swipe-to-delete with an undo snackbar. Deletes are soft; undo clears `deleted_at`.
- Tests: ViewModel state transitions with Turbine, plus a Compose UI test per state driven through the stateless `TransactionsScreen`.

## Slice 3 — Add / edit transaction

- New screen in `feature/transactions`. Amount, date, description, category, account, notes.
- The amount field must use `Money.parse` — no `toDouble()` anywhere in the input path.
- Setting a category manually sets `categoryLockedByUser = true`.
- Validation surfaced in UI state, not thrown.
- Navigation: `transactions` → `transactions/edit/{id}` and `transactions/new`. Keep the feature's `NavGraphBuilder` extension as the only entry point the app module sees.
- Tests: validation rules, and that editing bumps `local_revision` and sets a pending `UPSERT`.

## Slice 4 — Category picker and filtering

- Bottom-sheet category picker with search.
- Filter the list by category and by date range (this month / last month / custom).
- Filter state survives process death — use `SavedStateHandle`.
- Tests: filter composition, and that filters persist across `SavedStateHandle` restore.

---

## Constraints

- No new dependencies without asking.
- No new modules without asking.
- Every slice ends with `./gradlew testDebugUnitTest checkModuleBoundaries` passing.
- If a slice needs a schema change: migration + `MigrationTest` case + committed schema JSON, in the same commit. No exceptions.
- Match the existing comment style — explain non-obvious decisions, skip the obvious ones.

## Definition of done for M1

`./gradlew assembleDebug testDebugUnitTest lintDebug checkModuleBoundaries` all pass, the app is usable end-to-end offline, and every new repository behaviour has a test.
