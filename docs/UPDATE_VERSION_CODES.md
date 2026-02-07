# How to Update Version Codes

## Current Configuration

In `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 69  // Used by FULL version
    versionName = "38"
}

productFlavors {
    create("full") {
        // Uses defaultConfig versionCode (69)
        // No override
    }
    
    create("mini") {
        versionCode = 67  // Override for mini
    }
}
```

## How to Change VersionCodes

### Change Full Version VersionCode

Edit `app/build.gradle.kts` line 33:

```kotlin
defaultConfig {
    versionCode = 70  // ← Change this number
    versionName = "38"
}
```

**Full version will use this versionCode.**

### Change Mini Version VersionCode

Edit `app/build.gradle.kts` line 122:

```kotlin
create("mini") {
    dimension = "version"
    versionNameSuffix = "-mini"
    versionCode = 68  // ← Change this number
    buildConfigField("Boolean", "IS_MINI_VERSION", "true")
}
```

## Rebuild After Changes

After changing versionCode, you MUST clean and rebuild:

```bash
cd /Users/cfa532/Documents/GitHub/Tweet

# Clean build cache
./gradlew clean

# Rebuild mini version
./gradlew assembleMiniRelease

# Rebuild full version
./gradlew assembleFullRelease
```

## Verify VersionCodes

```bash
# Check mini
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/mini/release/app-mini-release.apk | grep "versionCode"

# Check full
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/full/release/app-full-release.apk | grep "versionCode"
```

## Recommended Version Planning

### For Next Release (v39)

**Option 1: Increment Both**
```kotlin
defaultConfig {
    versionCode = 70  // Full v39
}

create("mini") {
    versionCode = 69  // Mini v39
}
```

**Option 2: Only Release Full**
```kotlin
defaultConfig {
    versionCode = 70  // Full v39
}

create("mini") {
    versionCode = 67  // Keep old mini
}
```

## Quick Steps to Fix Your Current Issue

Your APK is showing 68 instead of 69 because of build cache:

```bash
# 1. Clean everything
./gradlew clean

# 2. Rebuild full version
./gradlew assembleFullRelease

# 3. Verify
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/full/release/app-full-release.apk | grep "versionCode"

# Should show: versionCode='69'
```

---

**Summary**: 
- Full version = defaultConfig.versionCode (line 33)
- Mini version = mini flavor versionCode (line 122)
- Always clean build after changing!

