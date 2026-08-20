package dev.charanjeev.finflow.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.finflow.core.database.FinFlowDatabase
import dev.charanjeev.finflow.core.database.Migrations
import dev.charanjeev.finflow.core.database.dao.CategoryDao
import dev.charanjeev.finflow.core.database.dao.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): FinFlowDatabase = Room.databaseBuilder(
        context,
        FinFlowDatabase::class.java,
        FinFlowDatabase.NAME,
    )
        .addMigrations(*Migrations.ALL)
        // Deliberately absent: fallbackToDestructiveMigration(). Silently wiping a
        // user's financial history on a schema change is not an acceptable failure
        // mode, so a missing migration must fail loudly instead.
        .build()

    @Provides
    fun provideTransactionDao(database: FinFlowDatabase): TransactionDao = database.transactionDao()

    @Provides
    fun provideCategoryDao(database: FinFlowDatabase): CategoryDao = database.categoryDao()
}
