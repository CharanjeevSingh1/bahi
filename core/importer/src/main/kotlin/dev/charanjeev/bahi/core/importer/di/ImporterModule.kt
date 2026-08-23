package dev.charanjeev.bahi.core.importer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.core.importer.CsvImporter
import dev.charanjeev.bahi.core.importer.DefaultCsvImporter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface ImporterModule {

    @Binds
    @Singleton
    fun bindCsvImporter(implementation: DefaultCsvImporter): CsvImporter
}
