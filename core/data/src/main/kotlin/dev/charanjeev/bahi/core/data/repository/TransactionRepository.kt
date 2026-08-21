package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The only surface features are allowed to touch. Room, DataStore and the sync
 * backend all live behind this interface, which is what lets a ViewModel test
 * run against a fake in milliseconds.
 */
interface TransactionRepository {

    fun observeTransactions(): Flow<List<Transaction>>

    fun observeTransaction(id: String): Flow<Transaction?>

    suspend fun upsert(transaction: Transaction)

    suspend fun delete(id: String)

    /** Returns the number of rows actually inserted after de-duplication. */
    suspend fun importAll(transactions: List<Transaction>): Int
}
