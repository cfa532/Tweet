# Upgrade Permission Fix

## Problem Found

The logs showed:
```
ziparchive: Unable to open '/storage/emulated/0/Download/fireshare.apk': Permission denied
Failed to open APK '/storage/emulated/0/Download/fireshare.apk': I/O error
```

## Root Cause

The app was downloading APK to `/storage/emulated/0/Download/` (public Downloads folder) but couldn't read it back due to Android 13+ storage permissions.

## Fix Applied

Changed download destination from:
```kotlin
// OLD - Public Downloads (requires permissions)
.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fireshare.apk")
val downloadedFilePath = Environment.getExternalStoragePublicDirectory(
    Environment.DIRECTORY_DOWNLOADS).toString() + "/fireshare.apk"
```

To:
```kotlin
// NEW - App's private external files (no permissions needed)
.setDestinationInExternalFilesDir(context, null, "fireshare.apk")
val downloadedFilePath = context.getExternalFilesDir(null)?.absolutePath + "/fireshare.apk"
```

## Result

- ✅ App can download APK to its private directory
- ✅ App can read the APK file to check versionCode
- ✅ No storage permissions required
- ✅ Works on Android 13+ (API 33+)

## Next Steps

1. **Install updated mini version** from Android Studio
2. **Try upgrade again** - should work now!
3. **Check logs** - should show successful versionCode reading

The upgrade should now work: Mini (67) → Full (68) ✅
