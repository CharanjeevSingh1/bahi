plugins {
    alias(libs.plugins.bahi.android.feature)
}

android {
    // Not feature.import -- "import" is a reserved word in both Java and
    // Kotlin. AGP rejects it as a namespace segment outright; more subtly,
    // Kotlin's own compiler and Hilt's KSP-generated glue code will accept a
    // `package dev.charanjeev.bahi.feature.import` declaration without
    // complaint but silently drop that segment when generating package
    // names for annotation-processor output, corrupting Hilt's generated
    // module/component classes in a way that surfaces as a KSP crash with
    // no reference to this being the cause. Every .kt file's own `package`
    // line has to avoid "import" too, not just this namespace string -- the
    // module's Gradle path stays :feature:import (docs/csv-import-design.md
    // §11.2, what shows up in the module graph); only the manifest package
    // and Kotlin package differ.
    namespace = "dev.charanjeev.bahi.feature.csvimport"
}

dependencies {
    implementation(projects.core.importer)
}
