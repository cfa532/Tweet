
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    alias(libs.plugins.firebase.crashlytics)
    id("kotlin-parcelize")
}

// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "us.fireshare.tweet"
    compileSdk = 36
    ndkVersion = "28.0.12433566"

    defaultConfig {
        applicationId = "us.fireshare.tweet"
        minSdk = 29
        targetSdk = 36
        versionCode = 95    // Full release version code. Must be increased each time,
                            // and higher than mini version code.
                            // So full version can override mini version. 
        versionName = "41"  // compared with App Mimei version to check for upgrade.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Enable 16 KB page size compatibility
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        
        // Default APP_ID (will be overridden by buildTypes)
        // This ensures APP_ID is always defined and prevents any inheritance issues
        buildConfigField("String", "APP_ID", "\"\"")
        buildConfigField("String", "BASE_URL", "\"\"")
    }
    
    signingConfigs {
        // Debug signing config (automatically available)
        
        create("release") {
            // Load from keystore.properties file or fall back to debug signing
            if (keystorePropertiesFile.exists()) {
                // Use rootProject.file() since keystore is in project root, not app/ directory
                storeFile = rootProject.file(keystoreProperties["KEYSTORE_FILE"].toString())
                storePassword = keystoreProperties["KEYSTORE_PASSWORD"].toString()
                keyAlias = keystoreProperties["KEY_ALIAS"].toString()
                keyPassword = keystoreProperties["KEY_PASSWORD"].toString()
            } else {
                // Fallback to debug signing if keystore.properties doesn't exist
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias = signingConfigs.getByName("debug").keyAlias
                keyPassword = signingConfigs.getByName("debug").keyPassword
            }
        }
    }
    
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
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
            // DEBUG BUILD CONFIG - Different from release
            buildConfigField("String", "BASE_URL", "\"twbe.fireshare.us\"")
            buildConfigField("String", "APP_ID", "\"d4lRyhABgqOnqY4bURSm_T-4FZ4\"")  // DEBUG APP_ID
            buildConfigField("String", "APP_ID_HASH", "\"5yOO4xP1QjAXhHpJtKMyIETVMxU\"")  // DEBUG APP_ID
            buildConfigField("String", "PACKAGE_ID", "\"9OCLYP-SXzen3e171-Ei_6N3Gwl\"")
            buildConfigField("String", "ALPHA_ID", "\"6IQc_t22JUub1TEgDP9Fo_Boosm\"")
            buildConfigField("String", "ENTRY_URLS", "\"VQ3xCeguhlAF1jY7zfn-HM_Vrad\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
//            isDebuggable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
            // RELEASE BUILD CONFIG - Different from debug
            buildConfigField("String", "BASE_URL", "\"tweet.fireshare.us\"")
            buildConfigField("String", "APP_ID", "\"heWgeGkeBX2gaENbIBS_Iy1mdTS\"")  // RELEASE APP_ID
            buildConfigField("String", "APP_ID_HASH", "\"h5U5jxPr2p2tg2kMr8UeyRMNIJ_\"")  // DEBUG APP_ID
            buildConfigField("String", "PACKAGE_ID", "\"9OCLYP-SXzen3e171-Ei_6N3Gwl\"")
            buildConfigField("String", "ALPHA_ID", "\"mwmQCHCEHClCIJy-bItx5ALAhq9\"")
            buildConfigField("String", "ENTRY_URLS", "\"dSXMdZNrpMw0xJQEbxPZn5nnLBK\"")
        }
    }
    
    flavorDimensions += "version"
    
    productFlavors {
        create("full") {
            dimension = "version"
            versionNameSuffix = ""
            // Full version uses default versionCode from defaultConfig (currently 70)
            // No override - will use defaultConfig versionCode
            buildConfigField("Boolean", "IS_MINI_VERSION", "false")
            buildConfigField("Boolean", "IS_PLAY_VERSION", "false")
            buildConfigField("String", "PLAY_SHARE_DOMAIN", "\"\"")
        }
        
        create("mini") {
            dimension = "version"
            versionNameSuffix = "-mini"
            versionCode = 87  // Mini version code. Must be smaller than full version's code
            buildConfigField("Boolean", "IS_MINI_VERSION", "true")
            buildConfigField("Boolean", "IS_PLAY_VERSION", "false")
            buildConfigField("String", "PLAY_SHARE_DOMAIN", "\"\"")
        }
        
        create("play") {
            dimension = "version"
            versionNameSuffix = "-play"
            versionCode = 95  // Play version code increased for release
            buildConfigField("Boolean", "IS_MINI_VERSION", "false")
            buildConfigField("Boolean", "IS_PLAY_VERSION", "true")
            buildConfigField("String", "PLAY_SHARE_DOMAIN", "\"gplay.fireshare.us\"")
            // Play version is based on full version but with different settings
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    // Configure Kotlin compiler options (migrated from kotlinOptions)
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // Create shared source set for full and play variants (both use FFmpeg)
    // Both variants share the same FFmpeg-based video processing code
    sourceSets {
        getByName("full") {
            java.srcDir("src/fullPlay/java")
        }
        getByName("play") {
            java.srcDir("src/fullPlay/java")
        }
    }
    @Suppress("UnstableApiUsage")
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude problematic binaries for 16 KB page size compatibility
            excludes += "**/dump_syms/**"
            excludes += "**/dump_syms.bin"
            excludes += "**/linux/dump_syms.bin"
            excludes += "**/mac/dump_syms.bin"
            excludes += "**/win32/dump_syms.exe"
            excludes += "**/win64/dump_syms.exe"
            // Note: .so files in lib/ are needed for FFmpeg, only exclude dump_syms
            pickFirsts += "**/lib/**"
        }
        // Enable 16 KB page size compatibility
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // FFmpeg Kit for local video processing - minimal 16KB build (ARM only)
    // Included in full and play versions, excluded in mini version
    "fullImplementation"(files("libs/ffmpeg-kit-16kb-minimal.aar"))
    "fullImplementation"("com.arthenica:smart-exception-java:0.2.1")
    "playImplementation"(files("libs/ffmpeg-kit-16kb-minimal.aar"))
    "playImplementation"("com.arthenica:smart-exception-java:0.2.1")

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    // OkHttp removed - using Ktor for all HTTP operations (consolidated)
    implementation(libs.accompanist.systemuicontroller)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    implementation(libs.timber)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.material)
    implementation(libs.firebase.crashlytics)
    implementation(libs.ui.graphics)
    implementation(libs.androidx.exifinterface)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.accompanist.pager)
    implementation(libs.accompanist.pager.indicators)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.material.icons.extended.android)
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
    implementation(libs.hprose.java)
    api(libs.androidx.navigation.fragment.ktx)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    // Coil removed - using custom ImageCacheManager instead
    implementation(libs.androidx.media3.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    
    // ShortcutBadger for launcher badge support
    implementation(libs.shortcutbadger)

    // Subsampling Scale Image View for efficient large image handling
    implementation(libs.subsampling.scale.image.view)

    // Support library for ExifInterface
    implementation(libs.androidx.exifinterface)

    // CameraX dependencies
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
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

    // WorkManager testing dependencies
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
