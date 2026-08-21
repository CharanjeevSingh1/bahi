pluginManagement {
    // Composite build: convention plugins live in build-logic and are compiled
    // before the main build, so every module shares one Gradle configuration.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bahi"

// Lets modules depend on each other as `projects.core.data` instead of
// project(":core:data") -- typo-proof and IDE-navigable.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:importer")
include(":core:sync")
include(":core:testing")

include(":feature:transactions")
include(":feature:budgets")
include(":feature:insights")
include(":feature:settings")
