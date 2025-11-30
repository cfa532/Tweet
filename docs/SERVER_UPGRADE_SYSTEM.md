# Server-Driven Upgrade System

## Overview

The app implements a **server-driven upgrade check** that works uniformly for both mini and full versions. When the server's versionName is **larger than** the app's versionName, a new full version upgrade is available for download. This applies to **mini and full versions only**; the **Play variant is upgraded through Google Play Store**, not via the server upgrade mechanism.

## How It Works

### Version Check Flow

1. **App launches** and waits 15 seconds
2. **Calls** `HproseInstance.checkUpgrade()` to query server
3. **Server returns** version info with `packageId`
4. **Compares** current app versionName with server versionName
5. **If server versionName > app versionName**, a new full version upgrade is available
6. **Shows upgrade dialog** with download link
7. **Downloads** full version APK from `http://{hostIp}/mm/{packageId}`

### Key Mechanism

```kotlin
// Server upgrade check (works for mini and full versions, NOT Play variant)
fun checkForUpgrade(context: Context) {
    // Play variant doesn't support server upgrades (uses Google Play Store)
    if (BuildConfig.IS_PLAY_VERSION) {
        return  // Skip upgrade check for Play variant
    }
    
    val versionInfo = HproseInstance.checkUpgrade() // Query server
    val currentVersion = versionName.replace("-mini", "").toInt()
    val serverVersion = versionInfo["version"]?.toInt()
    
    // If server versionName > app versionName, upgrade is available
    if (currentVersion < serverVersion) {
        // Show upgrade dialog with download URL for full version APK
        showUpdateDialog(context, "$hostIp/mm/${versionInfo["packageId"]}")
    }
}
```

## Version Naming

### Mini Version
- **versionName**: `"38-mini"`
- **versionCode**: `67` (must be smaller than full version)
- **For comparison**: Strips "-mini" → `38`
- **Receives**: Full version APK from server (becomes full version after upgrade)

### Full Version  
- **versionName**: `"38"`
- **versionCode**: `68` (must be higher than mini version)
- **For comparison**: `38` (no suffix)
- **Receives**: Newer full version APK from server

### Critical Requirement

**For mini → full upgrade to work correctly**:
- ✅ Mini versionCode **must be smaller** than full versionCode
- ❌ If mini versionCode ≥ full versionCode, Android will install them as separate apps
- **Example**: Mini (67) < Full (68) → Full replaces Mini ✅

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

### Play Variant Users
**Play variant does NOT use server upgrade mechanism**:
- ❌ No server upgrade check performed
- ❌ No upgrade dialog shown
- ✅ Upgrades managed by **Google Play Store**
- ✅ Users receive updates through Play Store's standard update mechanism
- **Reason**: Play Store policy requires all updates to go through the Play Store, not external APK downloads

## Upgrade Availability Rule

**When is an upgrade available?**
- ✅ **Server versionName > App versionName** → New full version upgrade available
- ❌ **Server versionName ≤ App versionName** → No upgrade needed

**Examples:**
- App versionName: "38" or "38-mini", Server versionName: "39" → Upgrade available ✅
- App versionName: "39", Server versionName: "39" → No upgrade needed
- App versionName: "40", Server versionName: "39" → No upgrade (app is newer)

## Two Upgrade Mechanisms

The app now has **two complementary upgrade mechanisms**:

### 1. Server-Driven Upgrade (Primary)
**Location**: `ActivityViewModel.checkForUpgrade()`

**Characteristics**:
- ✅ Server controls when upgrades are available
- ✅ Works for **mini and full versions only** (Play variant excluded)
- ✅ Uses server's package repository
- ✅ Authoritative source of truth
- ✅ Uniform experience for mini and full users
- ❌ **Play variant**: Upgraded through Google Play Store instead

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
            // Start server upgrade check after 15 seconds (mini and full only)
            // Play variant skips this check automatically
            activityViewModel.checkForUpgrade(this@TweetActivity)
            
            // Also start background download for mini version (fallback)
            if (BuildConfig.IS_MINI_VERSION) {
                upgradeManager.startDownload()
            }
        }
    }
}
```

**Note**: The `checkForUpgrade()` function automatically skips the upgrade check if `BuildConfig.IS_PLAY_VERSION` is true, as Play variant upgrades are managed by Google Play Store.

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

### For Play Variant Users
✅ **Google Play Store**: Standard Play Store update mechanism
✅ **No server checks**: Upgrade system is disabled for Play variant
✅ **Compliance**: Follows Play Store policies (no external APK downloads)

### For Developers
✅ **Single codebase**: No separate upgrade logic (except Play variant exclusion)
✅ **Server control**: Push upgrades to mini and full users at once
✅ **Flexible versioning**: Mini and full can have different version suffixes
✅ **Reliable**: Server is authoritative source for mini and full versions
✅ **Clear separation**: Play variant uses Play Store, others use server upgrade

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

### Publishing New Version (Mini & Full)

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

4. **All installations notified** (mini and full only):
- Mini version users: 38-mini → 39 (full)
- Full version users: 38 → 39 (full)
- **Play variant users**: Not affected (upgrade via Google Play Store)

### Publishing Play Variant Updates

**Play variant uses a separate deployment process**:
1. Build Play variant: `./gradlew assemblePlayRelease`
2. Upload APK/AAB to **Google Play Console**
3. Release through Play Store's standard update mechanism
4. **No server upgrade check** - Play Store handles notifications and downloads

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

### Test Play Variant (No Server Upgrade)

```bash
# Install Play variant
./gradlew assemblePlayDebug
adb install app/build/outputs/apk/play/debug/app-play-debug.apk

# Wait 15 seconds after app launch
# Expected: NO upgrade dialog (server upgrade disabled)
# Expected: Log shows "Play version detected, skipping upgrade check"
# Result: Play variant does not check for server upgrades
# Note: Play variant upgrades are managed by Google Play Store only
```

## Troubleshooting

### Upgrade Not Showing (Mini & Full Versions)
- Check server returns version info: `HproseInstance.checkUpgrade()`
- Verify version comparison: **server versionName must be > app versionName**
- Check logs: `adb logcat | grep checkForUpgrade`
- Ensure app is not Play variant (Play variant doesn't use server upgrade)

### Version Comparison Issues
- Ensure "-mini" suffix is stripped: `replace("-mini", "")`
- Verify both versions parse to Int correctly
- Check server version is valid integer
- **Remember**: Upgrade available when `serverVersionName > appVersionName`

### Download Fails
- Verify server URL is accessible: `http://{hostIp}/mm/{packageId}`
- Check network connectivity
- Verify DownloadManager permissions

### Play Variant Upgrade Questions
- **Q**: Why doesn't Play variant show upgrade dialog?
  - **A**: Play variant upgrades are managed by Google Play Store only. Server upgrade check is disabled for Play variant.
- **Q**: How do Play variant users get updates?
  - **A**: Through Google Play Store's standard update mechanism, same as any other Play Store app.
- **Q**: Can Play variant be upgraded via server?
  - **A**: No. Play Store policy requires all updates to go through Play Store, not external APK downloads.

## Related Documentation

- 📖 **Upgrade System**: `docs/UPGRADE_SYSTEM.md`
- 🏗️ **Build Configuration**: `docs/BUILD_FLAVORS.md`
- 📚 **Posting Restrictions**: `docs/POSTING_RESTRICTIONS.md`

---

**Last Updated**: December 2024  
**Implementation**: `TweetActivity.kt` (ActivityViewModel.checkForUpgrade)  
**Server API**: `HproseInstance.checkUpgrade()`  

## Summary

### Upgrade Availability
- ✅ **When server versionName > app versionName**: New full version upgrade is available
- ✅ **Applies to**: Mini and Full versions only
- ❌ **Play variant**: Upgraded through Google Play Store, not server upgrade mechanism

### Version Variants
- **Mini**: Uses server upgrade, downloads full version APK from server
- **Full**: Uses server upgrade, downloads newer full version APK from server  
- **Play**: Uses Google Play Store updates only (server upgrade disabled)

