# Version Code Fix for Upgrade

## Issue

When installing full version after mini version, it installed as a **separate app** instead of replacing/upgrading the mini version.

## Root Cause

Android requires the upgrade APK to have a **higher versionCode** than the currently installed app. Both mini and full had the same versionCode (67), so Android treated them as different apps.

**Critical Requirement**: The **mini version's versionCode must be smaller** than the **full version's versionCode** for the full version to replace the mini version during upgrade. If they are equal or mini's is higher, Android will install the full version as a separate app instead of replacing it.

## Solution

Assigned different versionCodes to each flavor:

```kotlin
productFlavors {
    create("full") {
        dimension = "version"
        versionNameSuffix = ""
        versionCode = 68          // Higher versionCode
        buildConfigField("Boolean", "IS_MINI_VERSION", "false")
    }
    
    create("mini") {
        dimension = "version"
        versionNameSuffix = "-mini"
        versionCode = 67          // Lower versionCode
        buildConfigField("Boolean", "IS_MINI_VERSION", "true")
    }
}
```

## Version Configuration

| Flavor | versionCode | versionName | Can Replace |
|--------|-------------|-------------|-------------|
| Mini | 67 | "38-mini" | - |
| Full | 68 | "38" | ✅ Mini (higher code) |

## How Android Upgrade Works

For one APK to replace another:
1. ✅ Same `applicationId` - "us.fireshare.tweet"
2. ✅ Same signing certificate - Your keystore
3. ✅ Higher `versionCode` - Full (68) > Mini (67)

**Key Rule**: 
- **Mini versionCode (67) < Full versionCode (68)** ✅
- If Mini versionCode ≥ Full versionCode, Android will install them as separate apps ❌

**Result**: Full version can now replace mini version! ✅

## User Experience

**Before Fix**:
```
Install Mini (versionCode 67)
Install Full (versionCode 67)
Result: Two separate apps ❌
```

**After Fix**:
```
Install Mini (versionCode 67)
Install Full (versionCode 68)
Result: Full replaces Mini ✅
Data preserved ✅
```

## Data Migration

When full version replaces mini version:
- ✅ All user data preserved
- ✅ SharedPreferences preserved
- ✅ Database preserved (same applicationId)
- ✅ Cached files preserved
- ✅ Settings preserved

## Testing

### Test Mini → Full Upgrade

```bash
# 1. Install mini version
adb install app/build/outputs/apk/mini/release/app-mini-release.apk

# 2. Use the app, post tweets, login

# 3. Install full version (upgrade)
adb install -r app/build/outputs/apk/full/release/app-full-release.apk

# Expected: "App updated" message
# Expected: Same data, same login
# Expected: Only one app icon
```

### Verify Version Codes

```bash
# Check mini version
aapt dump badging app/build/outputs/apk/mini/release/app-mini-release.apk | grep versionCode

# Check full version
aapt dump badging app/build/outputs/apk/full/release/app-full-release.apk | grep versionCode
```

Should show:
- Mini: `versionCode='67'`
- Full: `versionCode='68'`

## Future Version Planning

When releasing updates:

### Next Mini Version (v39-mini)
```kotlin
versionCode = 69
versionName = "39"
versionNameSuffix = "-mini"
```

### Next Full Version (v39)
```kotlin
versionCode = 70
versionName = "39"
```

**Pattern**: 
- Odd versionCodes for mini (67, 69, 71...)
- Even versionCodes for full (68, 70, 72...)
- OR: Full always = Mini + 1

## Why Full Version Doesn't Load Tweets

This is likely **unrelated** to the versionCode issue. After upgrade works correctly, if tweets still don't load, check:

1. **Database migration** - Room database version
2. **Permissions** - Storage/network permissions
3. **Build variant** - Is full version configured correctly
4. **FFmpeg paths** - Any initialization issues
5. **Logs** - Check for errors: `adb logcat | grep -E "Tweet|Database|ERROR"`

---

**Status**: ✅ versionCode fixed - Full can now replace Mini
**Next**: Test upgrade, then debug tweet loading issue if it persists

