# Direct Download for Mini Users

## Change Summary

Mini users now get **direct download** when clicking upgrade - no second confirmation dialog.

## Before vs After

### Before (Two Dialogs)
1. **First Dialog** (BottomNavigationBar): "需要升級" (Upgrade Required)
   - User clicks "立即升級"
2. **Second Dialog** (showUpdateDialog): "更新可用" (Update Available)
   - User clicks "更新" again
3. Download starts

### After (One Dialog)
1. **Only Dialog** (BottomNavigationBar): "需要升級" (Upgrade Required)
   - User clicks "立即升級"
2. **Download starts immediately** ✅

## Implementation

```kotlin
// In ActivityViewModel.checkForUpgrade()
if (isMiniVersion && immediate) {
    // Mini user manually requesting upgrade
    val versionInfo = HproseInstance.checkUpgrade()
    if (versionInfo != null && versionInfo["packageId"] != null) {
        val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
        
        // Download directly, bypass confirmation dialog
        downloadAndInstall(context, downloadUrl)  // Direct call!
    }
    return@launch
}
```

### Full Version Users
Still get confirmation dialog (unchanged):
1. Wait 15s after app launch
2. If server has newer version: "更新可用" dialog
3. User clicks "更新"
4. Download starts

## User Experience

### Mini User (> 5 Tweets)
```
Click Compose Button (+)
        ↓
Dialog: "需要升級"
        ↓
Click: "立即升級"
        ↓
✨ Download Starts Immediately ✨
        ↓
Notification: "Downloading Update"
        ↓
Download Complete
        ↓
Installer Opens
```

**One click to upgrade** - much better UX! ✅

### Full User (Automatic Check)
```
App Launch
        ↓
Wait 15s
        ↓
Server Check
        ↓
Dialog: "更新可用" (if newer version)
        ↓
Click: "更新"
        ↓
Download Starts
```

## Benefits

✅ **Faster**: One dialog instead of two
✅ **Clearer**: User already agreed in first dialog
✅ **Better UX**: Less clicking required
✅ **Consistent**: Mini users know they want to upgrade

## Testing

```bash
# Install updated mini version
adb install -r app/build/outputs/apk/mini/release/app-mini-release.apk

# Monitor logs
adb logcat | grep -E "checkForUpgrade|Download"

# Test flow:
# 1. Log in (non-guest, > 5 tweets)
# 2. Click compose button (+)
# 3. Dialog: "需要升級"
# 4. Click "立即升級"
# 5. Expected: Download notification appears immediately
# 6. Expected: NO second dialog
```

### Expected Logs
```
checkForUpgrade: Mini user requesting upgrade: 38-mini
checkForUpgrade: Starting direct download for mini user, url=...
DownloadManager: Downloading Update
```

---

**Status**: ✅ One-click upgrade for mini users
**Build**: `app/build/outputs/apk/mini/release/app-mini-release.apk`

