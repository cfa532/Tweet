# How To Update Version Codes

## Current Configuration

`app/build.gradle.kts` defines the shared app version in `defaultConfig`:

```kotlin
defaultConfig {
    versionCode = 138
    versionName = "68"
}
```

Both `full` and `play` use this value unless a flavor overrides it. The former `mini` flavor has been removed.

## Change The App Version

Edit `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 139
    versionName = "69"
}
```

If Play Store releases need a separate code, update the `play` flavor override:

```kotlin
create("play") {
    versionCode = 139
}
```

## Rebuild After Changes

```bash
cd /Users/cfa532/Documents/GitHub/Tweet
./gradlew clean
./gradlew assembleFullRelease
./gradlew assemblePlayRelease
```

## Verify Version Codes

```bash
/Users/cfa532/Library/Android/sdk/build-tools/37.0.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep "versionCode"

/Users/cfa532/Library/Android/sdk/build-tools/37.0.0/aapt dump badging \
  app/build/outputs/apk/play/release/app-play-release.apk | grep "versionCode"
```

## Summary

- Full version: uses `defaultConfig.versionCode`.
- Play version: uses the `play` flavor `versionCode` if set, otherwise `defaultConfig.versionCode`.
- Mini version: removed.
