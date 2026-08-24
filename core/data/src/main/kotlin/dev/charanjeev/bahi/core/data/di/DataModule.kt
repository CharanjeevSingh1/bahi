package dev.charanjeev.bahi.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.core.common.ApplicationScope
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstBudgetRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstCategoryRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstCategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstTransactionRepository
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindTransactionRepository(
        implementation: OfflineFirstTransactionRepository,
    ): TransactionRepository

    @Binds
    @Singleton
    fun bindCategoryRepository(
        implementation: OfflineFirstCategoryRepository,
    ): CategoryRepository

    @Binds
    @Singleton
    fun bindCategoryRuleRepository(
        implementation: OfflineFirstCategoryRuleRepository,
    ): CategoryRuleRepository

    @Binds
    @Singleton
    fun bindBudgetRepository(
        implementation: OfflineFirstBudgetRepository,
    ): BudgetRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Dispatcher(BahiDispatcher.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(BahiDispatcher.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * A SupervisorJob so one failed launch (e.g. a botched seed) can't cancel
     * unrelated application-scoped work sharing this scope.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @Dispatcher(BahiDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
