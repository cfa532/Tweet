// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    alias(libs.plugins.compose.compiler) apply false
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.3.2"  // Matches Kotlin 2.1.21
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("androidx.room") version "2.8.4" apply false
}
