package dev.charanjeev.bahi

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.charanjeev.bahi.core.common.ApplicationScope
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BahiApplication : Application() {

    @Inject
    lateinit var categoryRepository: CategoryRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Off the main thread and safe alongside the first read of categories:
        // seedSystemCategoriesIfNeeded only inserts ids that are absent, so it
        // never races a concurrent read into an inconsistent state -- at worst
        // the first emission just precedes the seeded rows.
        applicationScope.launch { categoryRepository.seedSystemCategoriesIfNeeded() }
    }
}
