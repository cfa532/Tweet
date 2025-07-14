// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
    alias(libs.plugins.compose.compiler) apply false
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1"
    id("com.google.gms.google-services") version "4.4.3" apply false
    id("androidx.room") version "2.7.2" apply false
}
