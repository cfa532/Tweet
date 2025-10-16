# Play Build Variant Documentation

## Overview
The "play" build variant is a third product flavor that's based on the full version but with some specific differences in configuration and AndroidManifest.xml settings.

## Build Variants Summary

| Variant | Version Code | Version Name | FFmpeg | Purpose |
|---------|-------------|--------------|--------|---------|
| **mini** | 67 | 38-mini | ❌ No | Lightweight version without local video processing |
| **full** | 69 | 38 | ✅ Yes | Complete version with local video processing |
| **play** | 70 | 38-play | ✅ Yes | Play Store version with specific configurations |

## Play Variant Configuration

### Build Configuration (`app/build.gradle.kts`)
```kotlin
create("play") {
    dimension = "version"
    versionNameSuffix = "-play"
    versionCode = 70  // Play version gets versionCode 70
    buildConfigField("Boolean", "IS_MINI_VERSION", "false")
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
1. **Deep Link Host**: Uses `play.fireshare.uk` instead of `fireshare.uk`
2. **HTTPS Scheme**: Uses `https://` instead of `http://`
3. **Intent Filter**: Play-specific deep link handling

```xml
<!-- Play version specific deep link handling -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" />
    <data android:host="play.fireshare.uk" />
    <data android:pathPattern="/tweet/.*" />
</intent-filter>
```

#### Permissions
- ✅ **All standard permissions**: Same as full version
- ✅ **Badge permissions**: Included for notification badges
- ✅ **Media permissions**: Full media access
- ✅ **Network permissions**: Internet and network state access

## Build Commands

### Build Play Release
```bash
./gradlew assemblePlayRelease
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
- **Version**: 38-play (versionCode: 70)
- **Size**: Similar to full version (includes FFmpeg libraries)

## Use Cases

### When to Use Play Variant
1. **Google Play Store**: For distribution through Google Play
2. **HTTPS Deep Links**: When you need secure deep link handling
3. **Different Domain**: When using `play.fireshare.uk` domain
4. **Version Management**: When you need a separate versioning scheme

### Differences from Full Version
1. **Domain**: Uses `play.fireshare.uk` instead of `fireshare.uk`
2. **Protocol**: Uses HTTPS instead of HTTP for deep links
3. **Version Code**: 70 instead of 69
4. **Version Name**: 38-play instead of 38

## File Structure

```
app/src/
├── main/                    # Shared code
├── full/                    # Full version specific
│   └── java/us/fireshare/tweet/video/
├── mini/                    # Mini version specific
│   └── java/us/fireshare/tweet/video/
└── play/                    # Play version specific
    ├── AndroidManifest.xml  # Play-specific manifest
    └── java/us/fireshare/tweet/video/  # Video processing (same as full)
```

## Testing

### Test Play Variant
1. **Build**: `./gradlew assemblePlayRelease`
2. **Install**: Install the generated APK
3. **Test Deep Links**: Test `https://play.fireshare.uk/tweet/...` links
4. **Test Features**: Verify all full version features work

### Verify Differences
1. **Version**: Check app shows "38-play" version
2. **Deep Links**: Test play-specific domain links
3. **FFmpeg**: Verify local video processing works
4. **Permissions**: Check all permissions are granted

## Deployment

### Google Play Store
- Use `assemblePlayRelease` for Play Store uploads
- Version code 70 ensures proper versioning
- HTTPS deep links for secure handling

### Direct Distribution
- Can be distributed alongside other variants
- Different package signature if using different keystores
- Separate versioning prevents conflicts

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

## Conclusion

The play variant provides a flexible way to create a Google Play Store version with:
- ✅ Full FFmpeg functionality
- ✅ Play-specific deep link handling
- ✅ Separate versioning scheme
- ✅ HTTPS security for deep links
- ✅ Easy maintenance and deployment

This allows for targeted distribution while maintaining the full feature set of the application.
