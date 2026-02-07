# Build Flavors Configuration

This project now supports **two product flavors** that allow you to build both mini and full versions from the same codebase without switching branches.

## Overview

| Flavor | Size | FFmpeg | Video Processing | Use Case |
|--------|------|--------|------------------|----------|
| **Full** | ~54 MB | ✅ Included | Local (offline) | Primary version with all features |
| **Mini** | ~10 MB | ❌ Excluded | Backend (online) | Lightweight initial download |

## Build Variants

The combination of flavors and build types creates these variants:

### Debug Variants
- `fullDebug` - Full version with debug features
- `miniDebug` - Mini version with debug features

### Release Variants
- `fullRelease` - Full production version with FFmpeg
- `miniRelease` - Mini production version without FFmpeg

## Building

### Using Gradle Commands

```bash
# Build full version (release)
./gradlew assembleFullRelease

# Build mini version (release)
./gradlew assembleMiniRelease

# Build full version (debug)
./gradlew assembleFullDebug

# Build mini version (debug)
./gradlew assembleMiniDebug

# Build all variants
./gradlew assemble
```

### Using Android Studio

1. Open **Build Variants** panel (View → Tool Windows → Build Variants)
2. Select desired variant from dropdown:
   - `fullDebug`
   - `fullRelease`
   - `miniDebug`
   - `miniRelease`
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**

## Output Locations

After building, APKs are generated in:

```
app/build/outputs/apk/
├── full/
│   ├── debug/
│   │   └── app-full-debug.apk
│   └── release/
│       └── app-full-release.apk
└── mini/
    ├── debug/
    │   └── app-mini-debug.apk
    └── release/
        └── app-mini-release.apk
```

## Configuration Details

### Build Config Fields

Both flavors set these BuildConfig constants:

**Full Version:**
```kotlin
BuildConfig.IS_MINI_VERSION = false
BuildConfig.FULL_APK_URL = ""
```

**Mini Version:**
```kotlin
BuildConfig.IS_MINI_VERSION = true
BuildConfig.FULL_APK_URL = "https://tweet.fireshare.uk/app/full/app-full-release.apk"
```

### Version Naming

- **Full**: `versionName` = "38" (from `defaultConfig`)
- **Mini**: `versionName` = "38-mini" (adds `-mini` suffix)

### Dependencies

The key difference is FFmpeg inclusion:

**Full Version:**
```kotlin
fullImplementation(files("libs/ffmpeg-kit-16kb-minimal.aar"))
fullImplementation("com.arthenica:smart-exception-java:0.2.1")
```

**Mini Version:**
- No FFmpeg dependencies
- Uses backend `/videoRoutes` endpoint for video conversion

## How It Works

### Full Version
1. Includes FFmpeg library (~44 MB)
2. Processes videos locally using `LocalHLSConverter` and `VideoNormalizer`
3. Works offline for video conversion
4. `BuildConfig.IS_MINI_VERSION = false`

### Mini Version
1. Excludes FFmpeg library
2. Routes video processing to backend using `BackendVideoConverterService`
3. Requires internet connection for video uploads
4. `BuildConfig.IS_MINI_VERSION = true`
5. Automatically downloads full version in background
6. Shows upgrade banner when full version is ready

## Code Usage

In your Kotlin code, check which version is running:

```kotlin
if (BuildConfig.IS_MINI_VERSION) {
    // Use backend video processing
    BackendVideoConverterService.processVideo(...)
} else {
    // Use local FFmpeg processing
    LocalVideoProcessingService.processVideo(...)
}
```

## Deployment Strategy

### 1. Build Both Versions

```bash
# Build mini version for initial distribution
./gradlew assembleMiniRelease

# Build full version for upgrade
./gradlew assembleFullRelease
```

### 2. Sign APKs

```bash
# Sign mini version
jarsigner -keystore your-keystore.jks \
  app/build/outputs/apk/mini/release/app-mini-release.apk \
  your-alias

# Sign full version
jarsigner -keystore your-keystore.jks \
  app/build/outputs/apk/full/release/app-full-release.apk \
  your-alias
```

### 3. Upload Full Version

Upload the full version to your server:
```
https://tweet.fireshare.uk/app/full/app-full-release.apk
```

This URL is configured in the mini version's `BuildConfig.FULL_APK_URL`.

### 4. Distribute Mini Version

- Upload mini version to Play Store or distribute directly
- Users download the lightweight 10 MB version
- Full version downloads in background automatically

## Testing

### Test Mini Version

```bash
# Install mini version
adb install app/build/outputs/apk/mini/release/app-mini-release.apk

# Monitor logs
adb logcat | grep -E "BackendVideoConverterService|UpgradeManager|ApkDownloadWorker"

# Test video upload (should use backend)
# - Upload a video
# - Verify it processes via backend
# - Check for upgrade banner on main feed
```

### Test Full Version

```bash
# Install full version
adb install app/build/outputs/apk/full/release/app-full-release.apk

# Monitor logs
adb logcat | grep -E "LocalHLSConverter|VideoNormalizer"

# Test video processing (should use FFmpeg)
# - Upload a video
# - Verify local processing
# - No upgrade banner should appear
```

## Benefits of Flavor-Based Approach

✅ **Single codebase** - No need to maintain separate branches
✅ **Easy switching** - Change variants in Android Studio instantly
✅ **Consistent code** - Share bug fixes and features across both versions
✅ **Flexible builds** - Build either or both versions as needed
✅ **Version control** - One source of truth for all builds

## Migration from Branch-Based Approach

Previously, mini and full versions were maintained in separate branches:
- **VideoOnMain** branch - Full version with FFmpeg
- **MiniVersion** branch - Mini version without FFmpeg

Now both versions are built from the same code using product flavors.

## Backend Requirements (Mini Version Only)

The mini version requires these backend endpoints:

### Upload Endpoint
**POST** `/videoRoutes`
- Accepts video file upload
- Returns `jobId` for status polling

### Status Endpoint  
**GET** `/videoRoutes/status/{jobId}`
- Returns conversion progress
- Status values: `uploading`, `processing`, `converting`, `completed`, `failed`
- Returns `cid` when complete

## Related Documentation

- 📖 **Mini Version Details**: `docs/MINI_VERSION_IMPLEMENTATION.md`
- 📋 **Quick Start**: `docs/MINI_VERSION_QUICK_START.md`
- 🏗️ **Technical Architecture**: `docs/TECHNICAL_ARCHITECTURE.md`

## Troubleshooting

### Build fails with FFmpeg errors in mini version

**Solution**: FFmpeg should only be included in full version. Check that dependencies use `fullImplementation` not `implementation`.

### Mini version still has large APK size

**Solution**: Verify you're building the mini variant:
```bash
./gradlew assembleMiniRelease
```

### Full version missing FFmpeg functionality

**Solution**: Check the FFmpeg AAR file exists in `app/libs/` directory.

### Build variants not showing in Android Studio

**Solution**: Sync Gradle files (File → Sync Project with Gradle Files)

---

**Last Updated**: October 14, 2025
**Configuration File**: `app/build.gradle.kts`

