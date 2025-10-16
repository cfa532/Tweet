# How to Change VersionCode Manually

## Edit build.gradle.kts

Open this file: `/Users/cfa532/Documents/GitHub/Tweet/app/build.gradle.kts`

## Change Full Version VersionCode

Find **line 33** and change the number:

```kotlin
defaultConfig {
    applicationId = "us.fireshare.tweet"
    minSdk = 29
    targetSdk = 36
    versionCode = 69    // ← Change this number (line 33)
    versionName = "38"
}
```

**Example**: Change `69` to `70` for next version

## Change Mini Version VersionCode

Find **line 122** and change the number:

```kotlin
productFlavors {
    create("mini") {
        dimension = "version"
        versionNameSuffix = "-mini"
        versionCode = 67  // ← Change this number (line 122)
        buildConfigField("Boolean", "IS_MINI_VERSION", "true")
    }
}
```

**Example**: Change `67` to `68` for next version

## After Changing

### 1. Clean Build
```bash
cd /Users/cfa532/Documents/GitHub/Tweet
./gradlew clean
```

### 2. Rebuild
```bash
# Rebuild the version you changed
./gradlew assembleMiniRelease    # If you changed mini
./gradlew assembleFullRelease    # If you changed full
./gradlew assemble               # Or rebuild both
```

### 3. Verify
```bash
# Check mini versionCode
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/mini/release/app-mini-release.apk | grep "versionCode"

# Check full versionCode  
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep "versionCode"
```

## Example: Preparing Version 39

```kotlin
// Line 33 - Full version
versionCode = 70
versionName = "39"

// Line 122 - Mini version  
versionCode = 69
versionName = "39"
```

Then:
```bash
./gradlew clean
./gradlew assembleMiniRelease assembleFullRelease
```

## Important Rules

✅ **Full versionCode must be higher than Mini** for upgrade to work
✅ **Always clean build** after changing versionCode
✅ **Increment by at least 1** for each new version

---

**Files to Edit**: `/Users/cfa532/Documents/GitHub/Tweet/app/build.gradle.kts`
- Line 33: Full version versionCode
- Line 122: Mini version versionCode

