package dev.charanjeev.bahi.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.database.dao.SyncConflictDao
import dev.charanjeev.bahi.core.database.dao.SyncShadowDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        CategoryRuleEntity::class,
        BudgetEntity::class,
        SyncShadowEntity::class,
        SyncConflictEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class BahiDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun syncShadowDao(): SyncShadowDao
    abstract fun syncConflictDao(): SyncConflictDao

    companion object {
        const val NAME = "bahi.db"
    }
}
