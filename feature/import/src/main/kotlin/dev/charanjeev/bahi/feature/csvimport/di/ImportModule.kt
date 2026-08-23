package dev.charanjeev.bahi.feature.csvimport.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.feature.csvimport.AndroidCsvFileReader
import dev.charanjeev.bahi.feature.csvimport.CsvFileReader

@Module
@InstallIn(SingletonComponent::class)
interface ImportModule {

    @Binds
    fun bindCsvFileReader(implementation: AndroidCsvFileReader): CsvFileReader
}
