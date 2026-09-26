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
  -> installed versionCode now equals the advertisement, so later checks stop
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

`versionCode` alone decides whether a release is newer. `versionName` remains a
required display/compatibility field in the current protocol, but it must never
be used for semantic ordering. Removing it requires a coordinated future client
and server protocol change.

## Recovery Behavior

- The DownloadManager id and complete release metadata are persisted together.
- Activity recreation observes the existing download instead of starting a
  second one.
- A completed download is revalidated before every installer launch.
- A failed, missing, cancelled, or metadata-mismatched download is cleared so a
  later check can start cleanly.
- The install mutex prevents lifecycle callbacks from opening duplicate Android
  installer screens.
- Provider discovery health-checks routes before constructing `/mm/<packageId>`.
  Unreachable provider advertisements are not download candidates.

Publication and operational verification are documented in
`SERVER_UPGRADE_SYSTEM.md`.

## Build Commands

```bash
./gradlew assembleFullRelease
./gradlew assemblePlayRelease
./gradlew bundlePlayRelease
```
