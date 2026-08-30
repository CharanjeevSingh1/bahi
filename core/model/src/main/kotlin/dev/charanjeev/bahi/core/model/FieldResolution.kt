package dev.charanjeev.bahi.core.model

/**
 * What happens to one field, on one row, where both devices changed it away
 * from the same base -- the only case a policy fires at all
 * (docs/sync-design.md §5.2's fourth row). Lives in `:core:model`, not
 * `:core:sync`, for the same reason [SyncOp] does: this is about the shape of
 * the payload, and the field-coverage test that checks every synced column
 * has an entry against one of these needs it reachable from both
 * `:core:data` and `:core:sync` without either depending on the other.
 *
 * The four cases are the M0 sketch's, kept because per-field resolution and
 * these four outcomes were the part of that sketch worth keeping (§5.1). What
 * changed is what each one means now that there is code behind it.
 */
enum class FieldResolution {

    /** Unconditionally keep the local value. Declared for completeness; no field currently uses it. */
    LOCAL_WINS,

    /** Unconditionally keep the remote value. Declared for completeness; no field currently uses it. */
    REMOTE_WINS,

    /**
     * Run the field's own merge algorithm instead of picking a side.
     * `transactions.notes` is the only field with one (§5.5): a substring
     * check, then a concatenation that keeps both texts rather than
     * discarding either.
     */
    MERGE,

    /**
     * A genuine tie with no principled winner: keep the value from whichever
     * side has the newer `updated_at`, break a tie in that by `deviceId`
     * lexicographically, and record the discarded value (§5.5, §5.6). The M0
     * name survives for continuity with the sketch, but nothing here prompts
     * anyone -- this is the default policy for a field with no more specific
     * rule, and the case [MERGE]'s notes algorithm itself falls back to when
     * one side is a cleared note rather than divergent text.
     */
    USER_PROMPT,

    /**
     * `transactions.category_id` and `transactions.category_locked_by_user`
     * only, resolved together rather than independently (§5.4): a locked
     * side beats an unlocked one outright, and only a same-lock-state
     * disagreement falls through to [USER_PROMPT]'s tiebreak. This is its own
     * case, not folded into [USER_PROMPT], because the asymmetric-lock branch
     * is not a tie and must never consult `updated_at` -- a hand-picked
     * category beats a newer rule guess, not an older one.
     */
    CATEGORY_LOCK_TIEBREAK,
}
