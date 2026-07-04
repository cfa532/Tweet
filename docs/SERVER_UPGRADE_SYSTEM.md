# Server Upgrade System

The mini flavor has been removed. The server-driven APK upgrade flow now applies to the direct-distribution `full` variant only.

## Variant Behavior

| Variant | Upgrade Source |
|---------|----------------|
| `full` | Server-driven APK upgrade |
| `play` | Google Play Store |

## Version Comparison

`ActivityViewModel.checkForUpgrade()` reads the installed `versionName`, asks the backend for the latest version, and compares both values as integers.

When the backend version is newer, the app resolves the package provider, builds a download URL, and shows the update dialog.

## Play Variant

`BuildConfig.IS_PLAY_VERSION` disables the server-driven APK flow for Play builds. Play updates should be published through Google Play.

## Release Steps

```bash
./gradlew assembleFullRelease
```

Upload the generated APK:

```text
app/build/outputs/apk/full/release/app-full-release.apk
```

Then update the backend upgrade metadata so `checkUpgrade()` returns the new `version` and `packageId`.

## Verification

```bash
adb logcat | grep checkForUpgrade
```

Expected behavior:

- Full build checks the backend and shows an update dialog when the backend version is newer.
- Play build logs that upgrade checks are skipped.
