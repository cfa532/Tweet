# Upgrade Flow

The mini variant has been removed, so upgrade behavior is no longer shared across mini and full builds.

## Current Flow

```text
Full app start
  -> checkForUpgrade()
  -> backend checkUpgrade()
  -> compare installed versionName with backend version
  -> show update dialog when backend version is newer
  -> download and install the full APK
```

## Variant Responsibilities

- `full`: uses the server-driven APK upgrade flow.
- `play`: skips APK self-updates and relies on Google Play.

## Code Paths

- `ActivityViewModel.checkForUpgrade()` handles backend version comparison.
- `ActivityViewModel.showUpdateDialog()` presents the upgrade prompt.
- `ActivityViewModel.downloadAndInstall()` downloads and installs the APK.

## Build Commands

```bash
./gradlew assembleFullRelease
./gradlew assemblePlayRelease
./gradlew bundlePlayRelease
```
