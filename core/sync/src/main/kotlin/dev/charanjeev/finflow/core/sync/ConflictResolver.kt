package dev.charanjeev.finflow.core.sync

/**
 * M4 lives here. Documenting the policy before writing the code, because the
 * policy is the interesting part -- last-write-wins on the whole row would
 * silently discard a category the user set on their phone while their tablet
 * was offline.
 *
 * Per-field resolution:
 *   amount, date, description  -> remote wins (they came from the bank)
 *   categoryId                 -> whichever side has categoryLockedByUser
 *   notes                      -> merge, newest first, if both changed
 *   deletion                   -> deletion always wins over an edit
 */
enum class FieldResolution { LOCAL_WINS, REMOTE_WINS, MERGE, USER_PROMPT }

interface ConflictResolver<T> {
    fun resolve(local: T, remote: T, base: T?): T
}
