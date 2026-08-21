package dev.charanjeev.bahi.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed interface Outcome<out T> {
    data class Success<T>(val data: T) : Outcome<T>
    data class Error(val throwable: Throwable) : Outcome<Nothing>
    data object Loading : Outcome<Nothing>
}

fun <T> Flow<T>.asOutcome(): Flow<Outcome<T>> = this
    .map<T, Outcome<T>> { Outcome.Success(it) }
    .onStart { emit(Outcome.Loading) }
    .catch { emit(Outcome.Error(it)) }
