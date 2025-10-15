# Server-Driven Upgrade System

## Overview

The app implements a **server-driven upgrade check** that works uniformly for both mini and full versions. When the server has a new version available, **all installations** (mini and full) are notified and can upgrade.

## How It Works

### Version Check Flow

1. **App launches** and waits 15 seconds
2. **Calls** `HproseInstance.checkUpgrade()` to query server
3. **Server returns** version info with `packageId`
4. **Compares** current version with server version
5. **If newer version exists**, shows upgrade dialog
6. **Downloads** from `http://{hostIp}/mm/{packageId}`

### Key Mechanism

```kotlin
// Server upgrade check (works for both mini and full versions)
fun checkForUpgrade(context: Context) {
    val versionInfo = HproseInstance.checkUpgrade() // Query server
    val currentVersion = versionName.replace("-mini", "").toInt()
    val serverVersion = versionInfo["version"]?.toInt()
    
    if (currentVersion < serverVersion) {
        // Show upgrade dialog with download URL
        showUpdateDialog(context, "$hostIp/mm/${versionInfo["packageId"]}")
    }
}
```

## Version Naming

### Mini Version
- **versionName**: `"38-mini"`
- **For comparison**: Strips "-mini" → `38`
- **Receives**: Full version APK from server (becomes full version after upgrade)

### Full Version  
- **versionName**: `"38"`
- **For comparison**: `38` (no suffix)
- **Receives**: Newer full version APK from server

## Unified Upgrade Path

### Mini Version Users
When server has version 39:
1. Current: "38-mini" → 38
2. Server: "39"
3. Comparison: 38 < 39 ✅
4. **Downloads**: Full version 39 from server
5. **Result**: Mini user upgrades to full version 39

### Full Version Users
When server has version 39:
1. Current: "38"
2. Server: "39"
3. Comparison: 38 < 39 ✅
4. **Downloads**: Full version 39 from server
5. **Result**: Full user upgrades to full version 39

## Two Upgrade Mechanisms

The app now has **two complementary upgrade mechanisms**:

### 1. Server-Driven Upgrade (Primary)
**Location**: `ActivityViewModel.checkForUpgrade()`

**Characteristics**:
- ✅ Server controls when upgrades are available
- ✅ Works for both mini and full versions identically
- ✅ Uses server's package repository
- ✅ Authoritative source of truth
- ✅ Uniform experience for all users

**Flow**:
```
App Start → Wait 15s → Check Server → Compare Versions → Show Dialog → Download
```

### 2. Background Download (Secondary/Fallback)
**Location**: `UpgradeManager` / `ApkDownloadWorker`

**Characteristics**:
- ✅ Mini version only
- ✅ Proactive background download
- ✅ Uses hardcoded URL (`BuildConfig.FULL_APK_URL`)
- ✅ Optional banner (can be dismissed)
- ✅ Fallback if server upgrade unavailable

**Flow**:
```
Mini App Start → Background Download → Banner Appears → User Upgrades When Ready
```

## Server API

### Endpoint
```
HproseInstance.checkUpgrade()
```

### Request
```kotlin
val params = mapOf(
    "aid" to appId,
    "ver" to "last",
    "entry" to "check_upgrade"
)
```

### Response
```kotlin
Map<String, String> {
    "version": "39",          // New version number
    "packageId": "abc123..."  // Package MimeiId for download
}
```

### Download URL
```
http://{hostIp}/mm/{packageId}
```

## User Experience

### Upgrade Dialog
When server has new version:

**Title**: "Update Available" (更新可用 / アップデート利用可能)
**Message**: "A new version is available" (有新版本可用 / 新しいバージョンが利用可能です)
**Buttons**:
- "Update" (更新 / 更新) - Downloads and installs
- "Cancel" (取消 / キャンセル) - Dismisses dialog

### Download Process
1. User clicks "Update"
2. DownloadManager downloads APK
3. Progress notification shown
4. On completion, Android installer opens
5. User confirms installation
6. App upgrades automatically

## Code Integration

### TweetActivity
```kotlin
@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private val activityViewModel: ActivityViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // ...
        lifecycleScope.launch {
            // Start server upgrade check after 15 seconds
            activityViewModel.checkForUpgrade(this@TweetActivity)
            
            // Also start background download for mini version (fallback)
            if (BuildConfig.IS_MINI_VERSION) {
                upgradeManager.startDownload()
            }
        }
    }
}
```

### Version Comparison
```kotlin
// Handles both "38" and "38-mini" correctly
val currentVersion = versionName.replace("-mini", "").toIntOrNull()
val serverVersion = versionInfo["version"]?.toIntOrNull()

if (currentVersion < serverVersion) {
    // Upgrade available
}
```

## Benefits

### For Mini Version Users
✅ **Two upgrade paths**:
1. Server-driven upgrade (when server pushes new version)
2. Background download (proactive, always ready)

✅ **Seamless transition**: Mini → Full version via server
✅ **No special handling**: Same mechanism as full version users
✅ **Always up-to-date**: Gets latest version from server

### For Full Version Users
✅ **Server-controlled upgrades**: Server decides when to push updates
✅ **Same experience**: Identical upgrade flow as mini users
✅ **Version continuity**: Full → Newer Full

### For Developers
✅ **Single codebase**: No separate upgrade logic
✅ **Server control**: Push upgrades to all users at once
✅ **Flexible versioning**: Mini and full can have different version suffixes
✅ **Reliable**: Server is authoritative source

## Implementation Details

### Version String Handling
```kotlin
// Mini version
versionName = "38-mini"
currentVersion = versionName.replace("-mini", "").toInt() // 38

// Full version
versionName = "38"
currentVersion = versionName.toInt() // 38

// Both compare against server version correctly
```

### Download Manager
- Uses Android's `DownloadManager`
- Shows system notification during download
- Saves to `Environment.DIRECTORY_DOWNLOADS`
- Filename: `fireshare.apk`

### Installation Intent
```kotlin
val installIntent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(downloadedApkUri, "application/vnd.android.package-archive")
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
            Intent.FLAG_GRANT_READ_URI_PERMISSION or 
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
context.startActivity(installIntent)
```

## Server Requirements

### Package Storage
Server must host APK files accessible via:
```
http://{hostIp}/mm/{packageId}
```

### Version Info
Server must provide:
- `version`: Integer version number
- `packageId`: MimeiId for downloading package

### Package Management
- **Full version APK** for both mini and full users
- **Version number** increments (38 → 39 → 40...)
- **PackageId** maps to stored APK file

## Deployment Workflow

### Publishing New Version

1. **Build full version**:
```bash
./gradlew assembleFullRelease
```

2. **Upload to server** with new version number:
```
Version: 39
PackageId: {new_mimei_id}
APK: http://server/mm/{new_mimei_id}
```

3. **Server updates version info**:
```kotlin
checkUpgrade() returns {
    "version": "39",
    "packageId": "{new_mimei_id}"
}
```

4. **All installations notified**:
- Mini version users: 38-mini → 39 (full)
- Full version users: 38 → 39 (full)

## Testing

### Test Server Upgrade Check

```bash
# Monitor upgrade check
adb logcat | grep -E "checkForUpgrade|checkUpgrade"

# Verify version comparison
# Current: 38-mini
# Server: 39
# Expected: Dialog appears
```

### Test Mini Version Upgrade

```bash
# Install mini version 38-mini
./gradlew assembleMiniDebug
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Wait 15 seconds after app launch
# Expected: Upgrade dialog if server has version 39
# Download: Full version 39
# Result: Mini → Full
```

### Test Full Version Upgrade

```bash
# Install full version 38
./gradlew assembleFullDebug
adb install app/build/outputs/apk/full/debug/app-full-debug.apk

# Wait 15 seconds after app launch
# Expected: Upgrade dialog if server has version 39
# Download: Full version 39
# Result: Full 38 → Full 39
```

## Troubleshooting

### Upgrade Not Showing
- Check server returns version info: `HproseInstance.checkUpgrade()`
- Verify version comparison logic
- Check logs: `adb logcat | grep checkForUpgrade`

### Version Comparison Issues
- Ensure "-mini" suffix is stripped: `replace("-mini", "")`
- Verify both versions parse to Int correctly
- Check server version is valid integer

### Download Fails
- Verify server URL is accessible: `http://{hostIp}/mm/{packageId}`
- Check network connectivity
- Verify DownloadManager permissions

## Related Documentation

- 📖 **Upgrade System**: `docs/UPGRADE_SYSTEM.md`
- 🏗️ **Build Configuration**: `docs/BUILD_FLAVORS.md`
- 📚 **Posting Restrictions**: `docs/POSTING_RESTRICTIONS.md`

---

**Last Updated**: October 14, 2025
**Implementation**: `TweetActivity.kt` (ActivityViewModel.checkForUpgrade)
**Server API**: `HproseInstance.checkUpgrade()`

