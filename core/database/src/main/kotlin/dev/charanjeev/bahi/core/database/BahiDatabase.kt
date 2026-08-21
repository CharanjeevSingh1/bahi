package dev.charanjeev.bahi.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BahiDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val NAME = "bahi.db"
    }
}
