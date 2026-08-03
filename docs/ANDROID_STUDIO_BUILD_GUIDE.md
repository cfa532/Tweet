# Android Studio Build & Install Guide

## Open The Project

```bash
open -a "Android Studio" /Users/cfa532/Documents/GitHub/Tweet
```

Wait for Gradle sync to complete.

## Select A Build Variant

1. Open **View -> Tool Windows -> Build Variants**.
2. In the `:app` module row, use the **Active Build Variant** dropdown.
3. Select one of the available variants:
   - `fullDebug`
   - `fullRelease`
   - `playDebug`
   - `playRelease`

The former `miniDebug` and `miniRelease` variants have been removed.

## Run Or Install

1. Connect an Android device with USB debugging enabled.
2. Select the target device in Android Studio.
3. Click **Run** or press `Ctrl+R`.

For build-only output, use **Build -> Build Bundle(s) / APK(s) -> Build APK(s)** and locate the generated APK.

## Command Line Builds

```bash
cd /Users/cfa532/Documents/GitHub/Tweet

# Full release APK
./gradlew assembleFullRelease

# Full debug APK
./gradlew assembleFullDebug

# Play release APK
./gradlew assemblePlayRelease

# Play release bundle
./gradlew bundlePlayRelease
```

## Output Paths

Full APKs:
- `app/build/outputs/apk/full/debug/app-full-debug.apk`
- `app/build/outputs/apk/full/release/app-full-release.apk`

Play APKs:
- `app/build/outputs/apk/play/debug/app-play-debug.apk`
- `app/build/outputs/apk/play/release/app-play-release.apk`

Play bundle:
- `app/build/outputs/bundle/playRelease/app-play-release.aab`

## Install With ADB

```bash
# Full release
adb install -r app/build/outputs/apk/full/release/app-full-release.apk

# Full debug
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

If you rely on Android Studio's bundled Android SDK, use:

```bash
/Users/cfa532/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

## Troubleshooting

### No devices found

- Check the USB connection.
- Enable USB debugging on the device.
- Try a different USB cable or port.

### Installation failed

- Uninstall the existing app first if signatures differ.
- Check device storage space.
- Verify device compatibility with `minSdk = 29`.

### Build variant not changing

- Use **File -> Sync Project with Gradle Files**.
- Wait for sync to complete before selecting a variant again.
