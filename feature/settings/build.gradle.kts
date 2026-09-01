plugins {
    alias(libs.plugins.bahi.android.feature)
}

android {
    namespace = "dev.charanjeev.bahi.feature.settings"
}

dependencies {
    // Not part of AndroidFeatureConventionPlugin's baseline set -- this is the
    // first feature module that needs it. SyncConfiguration is the only thing
    // pulled from it (docs/sync-design.md §13, slice 9a): the disabled-state
    // row reads whether this build has sync.properties at all.
    implementation(projects.core.sync)

    // Not part of the baseline set either -- this is the first screen that
    // needs to launch a system Activity Result flow (docs/sync-design.md
    // §8.6, slice 9d): the Drive consent PendingIntent is launched via
    // rememberLauncherForActivityResult, which needs this artifact.
    implementation(libs.androidx.activity.compose)
}
