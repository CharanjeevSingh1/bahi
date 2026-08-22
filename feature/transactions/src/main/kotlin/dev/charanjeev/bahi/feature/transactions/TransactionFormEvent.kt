package dev.charanjeev.bahi.feature.transactions

/** One-shot navigation signal -- not UI state, so it can't re-fire on rotation. */
sealed interface TransactionFormEvent {
    data object NavigateBack : TransactionFormEvent
}
