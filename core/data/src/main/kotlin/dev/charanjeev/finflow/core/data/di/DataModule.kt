package dev.charanjeev.finflow.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.finflow.core.common.Dispatcher
import dev.charanjeev.finflow.core.common.FinFlowDispatcher
import dev.charanjeev.finflow.core.data.repository.OfflineFirstTransactionRepository
import dev.charanjeev.finflow.core.data.repository.TransactionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindTransactionRepository(
        implementation: OfflineFirstTransactionRepository,
    ): TransactionRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Dispatcher(FinFlowDispatcher.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(FinFlowDispatcher.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
