# Play Build Variant

## Overview

The `play` flavor is the Google Play distribution variant. It keeps the full local video-processing feature set and applies Play-specific configuration, especially manifest changes for Play policy compliance.

The former `mini` flavor has been removed, so this project now builds `full` and `play` variants only.

## Build Variants

| Variant | Version Code | Version Name | FFmpeg | Purpose |
|---------|--------------|--------------|--------|---------|
| `full` | `defaultConfig.versionCode` | `defaultConfig.versionName` | Yes | Direct distribution |
| `play` | `play.versionCode` | `defaultConfig.versionName-play` | Yes | Google Play distribution |

## Play Configuration

`app/build.gradle.kts` defines:

```kotlin
create("play") {
    dimension = "version"
    versionNameSuffix = "-play"
    versionCode = 138
    buildConfigField("Boolean", "IS_PLAY_VERSION", "true")
    buildConfigField("String", "PLAY_SHARE_DOMAIN", "\"gplay.fireshare.us\"")
}
```

## Source Sets

```text
app/src/
├── main/       # Shared app code
├── fullPlay/   # FFmpeg-based video processing shared by full and play
├── full/       # Full-specific files when needed
└── play/       # Play-specific manifest/resources/code
```

## Manifest Differences

`app/src/play/AndroidManifest.xml` removes permissions that are not allowed for Play Store distribution, such as `REQUEST_INSTALL_PACKAGES`.

## Build Commands

```bash
# Play release APK
./gradlew assemblePlayRelease

# Play release AAB, recommended for Play Store uploads
./gradlew bundlePlayRelease

# Full and Play release APKs
./gradlew assembleFullRelease assemblePlayRelease
```

## Output Files

- APK: `app/build/outputs/apk/play/release/app-play-release.apk`
- AAB: `app/build/outputs/bundle/playRelease/app-play-release.aab`

## Use Cases

Use the `play` variant for:
- Google Play Store uploads.
- Play policy compliance.
- Builds that should not use the server-driven APK installer flow.

Use the `full` variant for direct distribution outside Google Play.

## Verification

```bash
# Check available build tasks
./gradlew tasks --group="build"

# Check APK manifest and package data
/Users/cfa532/Library/Android/sdk/build-tools/37.0.0/aapt dump badging \
  app/build/outputs/apk/play/release/app-play-release.apk
```

Expected permission behavior:

| Variant | REQUEST_INSTALL_PACKAGES | WRITE_EXTERNAL_STORAGE |
|---------|--------------------------|------------------------|
| `full` | Included | Included |
| `play` | Removed | Included |
