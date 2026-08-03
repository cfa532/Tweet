// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
    alias(libs.plugins.android.application) apply false

    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    alias(libs.plugins.compose.compiler) apply false
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    alias(libs.plugins.ksp) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("androidx.room") version "2.8.4" apply false
}
