# Upgrade Flow

The mini variant has been removed, so upgrade behavior is no longer shared across mini and full builds.

## Current Flow

```text
Full app start
  -> checkForUpgrade()
  -> backend checkUpgrade()
  -> require an enabled, complete release advertisement
  -> compare Android versionCode with backend versionCode
  -> resolve a healthy provider for the immutable package MiMei
  -> show update dialog when backend version is newer
  -> persist the release metadata and DownloadManager id
  -> verify size, SHA-256, package name and versionCode
  -> open Android's package installer
```

## Variant Responsibilities

- `full`: uses the server-driven APK upgrade flow.
- `play`: skips APK self-updates and relies on Google Play.

## Code Paths

- `ActivityViewModel.checkForUpgrade()` validates the advertisement and compares version codes.
- `ActivityViewModel.showUpdateDialog()` presents the upgrade prompt.
- `UpgradeDownloadState` owns persistent downloading, recovery and APK verification.

The download is stored in the app's external-files directory rather than the
shared Downloads directory. Android still enforces that an update is signed by
the same signing identity as the installed app.

## Build Commands

```bash
./gradlew assembleFullRelease
./gradlew assemblePlayRelease
./gradlew bundlePlayRelease
```
