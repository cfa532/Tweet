
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.fireshare.tweet"
    compileSdk = 35
    ndkVersion = "28.0.12433566"

    defaultConfig {
        applicationId = "com.fireshare.tweet"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1"   // compared with App Mimei version to check for upgrade.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildTypes {
        debug {
//            isMinifyEnabled = true
//            isShrinkResources = true
//            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            buildConfigField("String", "BASE_URL", "\"twbe.fireshare.uk\"")
            buildConfigField("String", "APP_ID", "\"d4lRyhABgqOnqY4bURSm_T-4FZ4\"")
            // default account to be followed by all users
            buildConfigField("String", "ALPHA_ID", "\"uTE6yhCWGLlkK6KGI9iMkOFZGGv\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
//            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            buildConfigField("String", "BASE_URL", "\"tweet1.fireshare.uk\"")
            buildConfigField("String", "APP_ID", "\"d4lRyhABgqOnqY4bURSm_T-4FZ4\"")
            buildConfigField("String", "ALPHA_ID", "\"uTE6yhCWGLlkK6KGI9iMkOFZGGv\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_19
        targetCompatibility = JavaVersion.VERSION_19
    }
    kotlinOptions {
        jvmTarget = "19"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.accompanist.systemuicontroller)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    implementation(libs.timber)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.material)
    implementation(libs.firebase.crashlytics.buildtools)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.accompanist.pager)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.hilt.common)
    implementation(libs.androidx.work.runtime)
    implementation(libs.work.runtime.ktx)

    implementation(libs.assisted.inject.annotations.dagger2)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.constraintlayout.compose)
    ksp(libs.assisted.inject.processor.dagger2)
    implementation(libs.gson)
    api(libs.androidx.navigation.fragment.ktx)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
    implementation(libs.logging.interceptor)
    implementation(libs.hprose.java)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
