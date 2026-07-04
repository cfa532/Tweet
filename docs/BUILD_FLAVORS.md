# Build Variants

This project supports two product flavors:

| Flavor | FFmpeg | Video Processing | Use Case |
|--------|--------|------------------|----------|
| `full` | Included | Local/offline | Direct distribution with the complete feature set |
| `play` | Included | Local/offline | Google Play distribution with Play-specific manifest settings |

The former `mini` flavor has been removed. There are no `assembleMini...` tasks or mini APK outputs.

## Variants

Debug variants:
- `fullDebug`
- `playDebug`

Release variants:
- `fullRelease`
- `playRelease`

## Build Commands

```bash
# Full release APK
./gradlew assembleFullRelease

# Full debug APK
./gradlew assembleFullDebug

# Play release APK
./gradlew assemblePlayRelease

# Play release bundle
./gradlew bundlePlayRelease

# All variants
./gradlew assemble
```

## Output Locations

```text
app/build/outputs/apk/
├── full/
│   ├── debug/app-full-debug.apk
│   └── release/app-full-release.apk
└── play/
    ├── debug/app-play-debug.apk
    └── release/app-play-release.apk

app/build/outputs/bundle/
└── playRelease/app-play-release.aab
```

## Configuration Notes

- `full` and `play` both use the FFmpeg implementation from `app/src/fullPlay/java`.
- `BuildConfig.IS_PLAY_VERSION` is `false` for `full` and `true` for `play`.
- `play` removes Play-restricted permissions through `app/src/play/AndroidManifest.xml`.

## Testing

```bash
# Install full release
adb install app/build/outputs/apk/full/release/app-full-release.apk

# Monitor local video processing logs
adb logcat | grep -E "LocalHLSConverter|VideoNormalizer"
```

## Troubleshooting

### Mini tasks are missing

That is expected. The mini product flavor has been removed.

### Full or Play version missing FFmpeg functionality

Check that `app/libs/ffmpeg-kit-16kb-mediacodec-arm64.aar` exists and that the flavor uses the shared `fullPlay` source set.

### Build variants not showing in Android Studio

Sync Gradle files with **File -> Sync Project with Gradle Files**.

---

**Configuration File**: `app/build.gradle.kts`
