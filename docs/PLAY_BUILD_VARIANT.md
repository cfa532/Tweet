# Play Build Variant Documentation

## Overview
The "play" build variant is a third product flavor that's based on the full version but with some specific differences in configuration and AndroidManifest.xml settings.

## Build Variants Summary

| Variant | Version Code | Version Name | FFmpeg | Purpose |
|---------|-------------|--------------|--------|---------|
| **mini** | 67 | 38-mini | ❌ No | Lightweight version without local video processing |
| **full** | 69 | 38 | ✅ Yes | Complete version with local video processing |
| **play** | 73 | 38-play | ✅ Yes | Play Store version with specific configurations |

## Play Variant Configuration

### Build Configuration (`app/build.gradle.kts`)
```kotlin
create("play") {
    dimension = "version"
    versionNameSuffix = "-play"
    versionCode = 73  // Play version code increased for release
    buildConfigField("Boolean", "IS_MINI_VERSION", "false")
    buildConfigField("Boolean", "IS_PLAY_VERSION", "true")
    // Play version is based on full version but with different settings
}
```

### Dependencies
- ✅ **FFmpeg Kit**: Included (same as full version)
- ✅ **Smart Exception**: Included (same as full version)
- ✅ **All other dependencies**: Same as full version

### Source Sets
- **Main source**: `app/src/main/` (shared code)
- **Play-specific**: `app/src/play/` (play-specific overrides)
- **Video processing**: `app/src/play/java/us/fireshare/tweet/video/` (copied from full)

## AndroidManifest.xml Differences

### Play-Specific Manifest (`app/src/play/AndroidManifest.xml`)

#### Key Differences from Main Manifest:
1. **Removed Restricted Permissions**: `REQUEST_INSTALL_PACKAGES` removed for Play Store compliance
2. **Deep Link Host**: Uses `fireshare.uk` (same as main)
3. **HTTP Scheme**: Uses `http://` (same as main)
4. **Intent Filter**: Standard deep link handling

```xml
<!-- Play version removes restricted permissions -->
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" tools:node="remove" />

<!-- Standard deep link handling -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="http" />
    <data android:host="fireshare.uk" />
    <data android:pathPattern="/tweet/.*" />
</intent-filter>
```

#### Permissions
- ❌ **REQUEST_INSTALL_PACKAGES**: Removed (Play Store restriction)
- ✅ **WRITE_EXTERNAL_STORAGE**: Kept (needed for photo saving)
- ✅ **All other permissions**: Same as full version
- ✅ **Badge permissions**: Included for notification badges
- ✅ **Media permissions**: Full media access
- ✅ **Network permissions**: Internet and network state access

## Build Commands

### Build Play Release APK
```bash
./gradlew assemblePlayRelease
```

### Build Play Release AAB (Recommended for Play Store)
```bash
./gradlew bundlePlayRelease
```

### Build All Variants
```bash
./gradlew assembleMiniRelease assembleFullRelease assemblePlayRelease
```

### Clean and Build
```bash
./gradlew clean assemblePlayRelease
```

## Output Files

### Play Release APK
- **Location**: `app/build/outputs/apk/play/release/app-play-release.apk`
- **Version**: 38-play (versionCode: 71)
- **Size**: 54 MB (includes FFmpeg libraries)

### Play Release AAB (Recommended)
- **Location**: `app/build/outputs/bundle/playRelease/app-play-release.aab`
- **Version**: 38-play (versionCode: 71)
- **Size**: 35.5 MB (dynamic delivery, smaller download)

## Use Cases

### When to Use Play Variant
1. **Google Play Store**: For distribution through Google Play
2. **Play Store Compliance**: Removes restricted permissions
3. **No Upgrade System**: Play Store handles updates automatically
4. **Version Management**: When you need a separate versioning scheme

### Differences from Full Version
1. **Permissions**: Removes `REQUEST_INSTALL_PACKAGES` (Play Store restriction)
2. **Upgrade System**: No upgrade functionality (Play Store handles updates)
3. **Version Code**: 71 instead of 69
4. **Version Name**: 38-play instead of 38
5. **Deep Links**: Uses `gplay.fireshare.us` for sharing (in TweetViewModel)

## File Structure

```
app/src/
├── main/                    # Shared code
├── full/                    # Full version specific
│   └── java/us/fireshare/tweet/video/
├── mini/                    # Mini version specific
│   └── java/us/fireshare/tweet/video/
└── play/                    # Play version specific
    ├── AndroidManifest.xml  # Play-specific manifest (removes restricted permissions)
    └── java/us/fireshare/tweet/
        ├── TweetActivity.kt # Play-specific activity (no upgrade system)
        └── video/           # Video processing (same as full)
            ├── LocalHLSConverter.kt
            └── VideoNormalizer.kt
```

## Testing

### Test Play Variant
1. **Build**: `./gradlew assemblePlayRelease` or `./gradlew bundlePlayRelease`
2. **Install**: Install the generated APK/AAB
3. **Test Deep Links**: Test `http://fireshare.uk/tweet/...` links
4. **Test Features**: Verify all full version features work
5. **Test Photo Saving**: Verify camera photos can be saved

### Verify Differences
1. **Version**: Check app shows "38-play" version
2. **Deep Links**: Test standard domain links work
3. **FFmpeg**: Verify local video processing works
4. **Permissions**: Check `REQUEST_INSTALL_PACKAGES` is NOT present
5. **No Upgrade**: Verify no upgrade system functionality

## Deployment

### Google Play Store
- Use `bundlePlayRelease` for Play Store uploads (recommended)
- Use `assemblePlayRelease` for APK uploads
- Version code 71 ensures proper versioning
- No restricted permissions for Play Store compliance

### Direct Distribution
- Can be distributed alongside other variants
- Different package signature if using different keystores
- Separate versioning prevents conflicts
- No upgrade system (Play Store handles updates)

## Maintenance

### Adding Play-Specific Features
1. **Code**: Add to `app/src/play/java/` directory
2. **Resources**: Add to `app/src/play/res/` directory
3. **Manifest**: Modify `app/src/play/AndroidManifest.xml`

### Updating Dependencies
- Play variant inherits from main dependencies
- Add play-specific dependencies with `"playImplementation"`
- Update FFmpeg libraries in both full and play variants

## Troubleshooting

### Common Issues
1. **Build Failures**: Check if play-specific files exist
2. **Deep Link Issues**: Verify manifest intent filters
3. **Version Conflicts**: Ensure unique version codes
4. **Dependency Issues**: Check FFmpeg libraries are included

### Debug Commands
```bash
# Check available variants
./gradlew tasks --group="build"

# Build with verbose output
./gradlew assemblePlayRelease --info

# Check APK contents
aapt dump badging app/build/outputs/apk/play/release/app-play-release.apk
```

## Permission Verification

### Build Package Verification Results:

| Variant | APK Size | REQUEST_INSTALL_PACKAGES | WRITE_EXTERNAL_STORAGE | Status |
|---------|----------|-------------------------|----------------------|---------|
| **Mini** | 10.9 MB | ✅ **INCLUDED** | ✅ **INCLUDED** | ✅ Correct |
| **Full** | 54 MB | ✅ **INCLUDED** | ✅ **INCLUDED** | ✅ Correct |
| **Play** | 54 MB | ❌ **REMOVED** | ✅ **INCLUDED** | ✅ Correct |

### Verification Commands:
```bash
# Check manifest permissions
grep "REQUEST_INSTALL_PACKAGES" app/build/intermediates/manifest_merge_blame_file/*/process*MainManifest/manifest-merger-blame-*-report.txt

# Check APK contents
aapt dump badging app/build/outputs/apk/play/release/app-play-release.apk
```

## Conclusion

The play variant provides a flexible way to create a Google Play Store version with:
- ✅ Full FFmpeg functionality
- ✅ Play Store compliance (no restricted permissions)
- ✅ Separate versioning scheme (versionCode: 71)
- ✅ No upgrade system (Play Store handles updates)
- ✅ Photo saving capability preserved
- ✅ Easy maintenance and deployment

This allows for targeted distribution while maintaining the full feature set of the application and ensuring Play Store compliance.
