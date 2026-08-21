package dev.charanjeev.bahi.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.Migrations
import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): BahiDatabase = Room.databaseBuilder(
        context,
        BahiDatabase::class.java,
        BahiDatabase.NAME,
    )
        .addMigrations(*Migrations.ALL)
        // Deliberately absent: fallbackToDestructiveMigration(). Silently wiping a
        // user's financial history on a schema change is not an acceptable failure
        // mode, so a missing migration must fail loudly instead.
        .build()

    @Provides
    fun provideTransactionDao(database: BahiDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideCategoryDao(database: BahiDatabase): CategoryDao = database.categoryDao()
}
