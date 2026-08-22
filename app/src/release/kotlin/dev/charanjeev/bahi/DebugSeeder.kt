package dev.charanjeev.bahi

import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import kotlinx.coroutines.CoroutineScope

/** The release build's DebugSeeder.kt: see src/debug for what this replaces. */
fun seedTransactionsForDevelopment(repository: TransactionRepository, scope: CoroutineScope) = Unit
