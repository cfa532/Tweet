# Play Variant Configuration

## Overview
The Play variant is a specialized build of the Tweet app designed for Google Play Store distribution. It's based on the full version but with specific modifications for store compliance and functionality.

## Build Configuration

### Gradle Settings (`app/build.gradle.kts`)
```kotlin
create("play") {
    dimension = "version"
    versionNameSuffix = "-play"
    versionCode = 70  // Play version gets versionCode 70
    buildConfigField("Boolean", "IS_MINI_VERSION", "false")
    buildConfigField("Boolean", "IS_PLAY_VERSION", "true")
    // Play version is based on full version but with different settings
}
```

### Dependencies
- **FFmpeg**: ✅ Included (same as full version)
- **Video Processing**: ✅ Full local video processing capabilities
- **Upgrade System**: ❌ Disabled (no upgrade functionality)

## Source Files

### Play-Specific Files
- `app/src/play/AndroidManifest.xml` - Store-compliant manifest
- `app/src/play/java/us/fireshare/tweet/TweetActivity.kt` - Simplified activity without upgrade functionality
- `app/src/play/java/us/fireshare/tweet/video/LocalHLSConverter.kt` - FFmpeg-based video conversion
- `app/src/play/java/us/fireshare/tweet/video/VideoNormalizer.kt` - FFmpeg-based video normalization

### Shared Files (with conditional logic)
- `app/src/main/java/us/fireshare/tweet/viewmodel/TweetViewModel.kt` - Uses `gplay.fireshare.us` for deep links

## Key Differences from Full Version

### 1. AndroidManifest.xml
- **Removed Permissions**:
  - `REQUEST_INSTALL_PACKAGES` (not allowed on Play Store)
  - `WRITE_EXTERNAL_STORAGE` (not needed for Play Store version)
- **Deep Links**: Uses `fireshare.uk` host
- **Clean Manifest**: Based on StoreVersion2 branch

### 2. TweetActivity.kt
- **No Upgrade Functionality**: Completely removed upgrade system
- **Simplified Code**: Based on StoreVersion2 branch
- **Core Features Only**: App initialization, notifications, themes
- **No Upgrade Checks**: No `checkForUpgrade()` or `checkForMiniUpgrade()` calls

### 3. TweetViewModel.kt
- **Play-Specific Deep Links**: Uses `http://gplay.fireshare.us/tweet/...`
- **Conditional Logic**: 
  ```kotlin
  val deepLink = if (BuildConfig.IS_PLAY_VERSION) {
      "http://gplay.fireshare.us/tweet/${tweet.mid}/${tweet.authorId}"
  } else {
      "http://${map["domain"]}/tweet/${tweet.mid}/${tweet.authorId}"
  }
  ```

### 4. Video Processing
- **Full FFmpeg Support**: Same as full version
- **Local Processing**: Complete video conversion and normalization
- **No Restrictions**: All video features available

## Build Commands

### Build Play Variant
```bash
./gradlew assemblePlayRelease
```

### Build All Variants
```bash
./gradlew assembleFullRelease assembleMiniRelease assemblePlayRelease
```

## Version Information
- **Version Code**: 70
- **Version Name**: `38-play`
- **Application ID**: `us.fireshare.tweet`
- **Target SDK**: 36
- **Min SDK**: 29

## Store Compliance Features
- ✅ No upgrade functionality (Play Store handles updates)
- ✅ No external APK installation permissions
- ✅ Clean manifest without restricted permissions
- ✅ Standard deep link handling
- ✅ Full video processing capabilities

## Testing
The Play variant can be tested by:
1. Building with `./gradlew assemblePlayRelease`
2. Installing the generated APK
3. Verifying no upgrade dialogs appear
4. Testing video processing functionality
5. Verifying deep links use `gplay.fireshare.us`

## Notes
- The Play variant is essentially the full version without upgrade functionality
- All core features (tweeting, video processing, etc.) are preserved
- Deep links are hardcoded to use the Play Store domain
- No server-driven upgrade system (Play Store handles app updates)
