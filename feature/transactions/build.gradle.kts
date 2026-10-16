plugins {
    alias(libs.plugins.bahi.android.feature)
}

android {
    namespace = "dev.charanjeev.bahi.feature.transactions"
}

dependencies {
    // ReportDrawnWhen (TransactionsRoute). Not in AndroidFeatureConventionPlugin's
    // baseline set: it already compiles here through navigation-compose's
    // transitive api, but a startup measurement should not hang on that.
    implementation(libs.androidx.activity.compose)
}
