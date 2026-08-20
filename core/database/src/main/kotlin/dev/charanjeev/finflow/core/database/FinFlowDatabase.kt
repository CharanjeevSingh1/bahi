package dev.charanjeev.finflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.charanjeev.finflow.core.database.dao.CategoryDao
import dev.charanjeev.finflow.core.database.dao.TransactionDao
import dev.charanjeev.finflow.core.database.entity.CategoryEntity
import dev.charanjeev.finflow.core.database.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FinFlowDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val NAME = "finflow.db"
    }
}
