# Server Upgrade System

The mini flavor has been removed. The server-driven APK upgrade flow now applies to the direct-distribution `full` variant only.

## Variant Behavior

| Variant | Upgrade Source |
|---------|----------------|
| `full` | Server-driven APK upgrade |
| `play` | Google Play Store |

## Release Advertisement

`check_upgrade` retains the legacy `version` field for older clients and adds
the fields used by the verified updater:

```json
{
  "enabled": true,
  "versionCode": 160,
  "versionName": "77",
  "packageId": "the-package-mimei",
  "size": 12345678,
  "sha256": "64-lowercase-hex-characters",
  "mission": "minor"
}
```

The app offers an update only when `enabled` is true and `versionCode` is
greater than the installed Android version code. It resolves a healthy package
provider, then verifies the completed APK's size, SHA-256, package name and
version code before opening Android's installer.

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

Compute the signed APK's exact byte count and SHA-256. In the Go backend's
upgrade constants, update the legacy version, version code, version name, size,
checksum and mission. Publish that exact APK under the package MiMei first;
only then set `upgradeEnabled` to `true` and deploy the backend.

Keep the advertisement disabled whenever the package or final metadata is not
ready. An enabled advertisement with incomplete metadata is rejected by both
the backend and Android client.

## Verification

```bash
adb logcat | grep checkForUpgrade
```

Expected behavior:

- Full build checks the backend and shows an update dialog when an enabled, verified release has a greater version code.
- Play build logs that upgrade checks are skipped.
