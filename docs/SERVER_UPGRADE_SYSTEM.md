# Server Upgrade System

Tweet's server-driven APK upgrade applies only to the direct-distribution
`full` flavor. The `play` flavor must be upgraded through Google Play and does
not contain the package-install permission or download receiver.

## Release contract

`check_upgrade` returns a complete description of one signed APK:

```json
{
  "enabled": true,
  "version": 77,
  "versionCode": 160,
  "versionName": "77",
  "packageId": "the-package-mimei",
  "size": 23844762,
  "sha256": "595107b5ec064e4fd0f7532b947440c71635d822ac61e0ae547c719a2a985ed3",
  "mission": "minor",
  "domain": "t1.w33w.site"
}
```

`versionCode` is the authoritative ordering key. It must increase for every
published APK, even when the application code is otherwise unchanged.
`versionName` is display and compatibility metadata; `version` exists for old
clients that compared the numeric display version. Keep all three fields until
those clients have been retired and the current parser has been changed in a
separate client release.

The client offers an update only when `enabled` is true and the advertised
`versionCode` is greater than `BuildConfig.VERSION_CODE`. Before Android's
installer is opened, the downloaded file must match the advertised byte count,
SHA-256, Android package name, and version code. Android then enforces the
installed application's signing identity.

## Safe publication order

The package must be available before the server advertises it. Reversing these
steps creates a window in which clients can select an old provider payload and
correctly reject it as corrupt.

1. Increase `defaultConfig.versionCode` and `defaultConfig.versionName` in
   `app/build.gradle.kts`. Keep the Play flavor's explicit version code in sync.
2. Commit and push the Android version change so the APK is reproducible from a
   known source revision.
3. Build the signed direct-distribution APK:

   ```bash
   ./gradlew :app:assembleFullRelease
   ```

4. Inspect the final artifact, not an intermediate APK:

   ```bash
   APK=app/build/outputs/apk/full/release/app-full-release.apk
   stat -f '%z' "$APK"
   shasum -a 256 "$APK"
   "$ANDROID_HOME/build-tools/37.0.0/aapt" dump badging "$APK"
   "$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs "$APK"
   ```

   Confirm the package is `us.fireshare.tweet`, the version code/name are the
   intended values, and the signing certificate matches the installed release.

5. Copy the APK to `~/tweet/tweet1/release.apk` on `minipc`, verify its size and
   checksum there, and publish the existing package MiMei:

   ```bash
   scp "$APK" minipc:~/tweet/tweet1/release.apk
   ssh minipc 'cd ~/tweet/tweet1 && sha256sum release.apk'
   ssh minipc 'cd ~/tweet/tweet1 && ./release.sh release.apk'
   ```

   The script must finish `upload`, `backup`, and `MiMeiPublish` successfully.

6. Ask production for the package's current providers. Download
   `/mm/<packageId>` through every address that the client health check can
   select, then compare the complete byte count and SHA-256. Do not activate
   the advertisement while a selectable provider serves stale bytes.
7. In `TweetBackendApp/file_entries.go`, update `upgradeVersion`,
   `upgradeVersionCode`, `upgradeVersionName`, `upgradePackageSize`, and
   `upgradePackageSHA`. Leave `upgradeEnabled` false until the preceding checks
   pass; for an already active release it may remain true only because the old
   complete advertisement stays deployed until this new backend revision is
   published.
8. Compile-check, commit, and push the backend. Copy its Go sources to
   `/home/pi/demo/tweet1/` on gen8 and run `/home/pi/demo/tweet1.sh`.
9. Verify `check_upgrade` by the new numbered backend revision first, then by
   `last`. Both responses must contain the exact APK metadata.

Never modify the metadata to match whatever bytes happen to be on a provider.
The signed APK is the release artifact; its measured properties define the
advertisement.

## Verified release record

The 76 to 77 release established the server and package side of this design:

| Item | Value |
|---|---|
| Installed release | version 76, version code 159 |
| Upgrade release | version 77, version code 160 |
| Package MiMei data version | 229 |
| Production backend revision | 1366 |
| APK size | 23,844,762 bytes |
| APK SHA-256 | `595107b5ec064e4fd0f7532b947440c71635d822ac61e0ae547c719a2a985ed3` |

Every reachable provider returned the exact APK. One stale IPv6 advertisement
was unreachable and therefore rejected by the same health check used before a
download; it should still be removed from provider advertising when practical.

## Device verification

Launch or relaunch a connected version 76 full build. It should offer version
77, restore or observe the DownloadManager job if the activity is recreated,
verify the completed APK, and open Android's installer. After version 77 is
installed, another check must not prompt because the installed and advertised
version codes are both 160.

For diagnosis:

```bash
adb logcat | grep -E 'checkForUpgrade|UpgradeDownload'
```

An end-to-end device install is the final proof of the Android handoff; server,
provider, and APK verification alone cannot confirm the user's package-installer
permission flow.
