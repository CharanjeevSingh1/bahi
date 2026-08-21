package dev.charanjeev.bahi

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Version catalogs aren't directly visible from precompiled plugin code, so
 * convention plugins reach them through this accessor.
 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.version(alias: String): String =
    findVersion(alias).get().requiredVersion

fun VersionCatalog.intVersion(alias: String): Int =
    version(alias).toInt()
