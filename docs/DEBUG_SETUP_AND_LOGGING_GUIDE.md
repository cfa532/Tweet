# Debug Setup And Logging Guide

## Build Variants

The active variants are:

- `fullDebug`
- `fullRelease`
- `playDebug`
- `playRelease`

The mini variant has been removed.

## Build And Install

```bash
cd /Users/cfa532/Documents/GitHub/Tweet

# Full debug
./gradlew assembleFullDebug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk

# Full release
./gradlew assembleFullRelease
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
```

Use the Android SDK `adb` directly if it is not on `PATH`:

```bash
/Users/cfa532/Library/Android/sdk/platform-tools/adb install -r \
  app/build/outputs/apk/full/debug/app-full-debug.apk
```

## Useful Logs

```bash
adb logcat | grep -E "checkForUpgrade|MediaUploadService|LocalHLSConverter|VideoNormalizer"
```

## Upgrade Checks

- `full` can use the server-driven APK upgrade flow.
- `play` skips the server-driven APK installer flow because Google Play manages updates.

## Video Processing

Both `full` and `play` include FFmpeg and use local video processing from `app/src/fullPlay/java`.
