package dev.charanjeev.bahi.core.common

import javax.inject.Qualifier

/**
 * Injecting dispatchers rather than referencing Dispatchers.IO directly is what
 * makes the data layer testable with a single TestDispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: BahiDispatcher)

enum class BahiDispatcher { IO, Default }

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
