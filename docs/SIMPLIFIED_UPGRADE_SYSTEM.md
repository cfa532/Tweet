# Simplified Upgrade System

## Overview

The app uses a **single, unified upgrade system** where both mini and full version users receive upgrades from the **server**. This eliminates redundancy and ensures all users get the same authoritative package.

## Key Principle

**All upgrades use the server package via `checkForUpgrade()`**

There is NO separate background download system. Everything goes through the server.

## How It Works

### 1. Automatic Server Check
**Trigger**: 15 seconds after app launch
**Applies to**: All users (mini and full versions)

```kotlin
// In TweetActivity.kt
launch {
    delay(15000)
    activityViewModel.checkForUpgrade(this@TweetActivity)
}
```

**Process**:
1. Queries server: `HproseInstance.checkUpgrade()`
2. Server returns: `{ "version": "39", "packageId": "abc123..." }`
3. Compares versions (strips "-mini" suffix for comparison)
4. If newer: Shows upgrade dialog
5. Downloads from: `http://{hostIp}/mm/{packageId}`
6. Opens Android installer

### 2. Posting Restriction (Mini Version > 5 Tweets)
**Trigger**: User tries to post when having > 5 tweets
**Applies to**: Mini version non-guest users only

```kotlin
// In ComposeTweetScreen.kt
if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
    showUpgradeRequiredDialog = true
}
```

**Process**:
1. Blocks posting
2. Shows upgrade dialog
3. User clicks "Upgrade Now"
4. Calls: `activityViewModel.checkForUpgrade(context)`
5. Same server upgrade flow as automatic check

### 3. Node Setup Alert (Full Version > 10 Tweets)
**Trigger**: User tries to post when having > 10 tweets and no node
**Applies to**: Full version non-guest users only

```kotlin
// In ComposeTweetScreen.kt
if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && 
    appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
    showNodeRequiredDialog = true
}
```

**Process**:
1. Blocks posting
2. Shows alert dialog
3. User must set up cloudDrivePort
4. No upgrade involved (just a reminder)

## Version Comparison

### Mini Version
- **versionName**: `"38-mini"`
- **Comparison**: Strips "-mini" → `38`
- **Server has 39**: Shows upgrade
- **Downloads**: Full version 39
- **Result**: Mini 38 → Full 39

### Full Version
- **versionName**: `"38"`
- **Comparison**: Direct → `38`
- **Server has 39**: Shows upgrade
- **Downloads**: Full version 39
- **Result**: Full 38 → Full 39

## Build Configuration

```kotlin
// app/build.gradle.kts
productFlavors {
    create("full") {
        dimension = "version"
        versionNameSuffix = ""
        buildConfigField("Boolean", "IS_MINI_VERSION", "false")
    }
    
    create("mini") {
        dimension = "version"
        versionNameSuffix = "-mini"
        buildConfigField("Boolean", "IS_MINI_VERSION", "true")
    }
}
```

**That's it!** No `FULL_APK_URL`, no background download configuration needed.

## Upgrade Flow Diagram

```
┌────────────────────────────────────┐
│         App Launches               │
│    (Mini or Full Version)          │
└──────────────┬─────────────────────┘
               │
               ▼
     ┌─────────────────────┐
     │ Wait 15 seconds     │
     └─────────┬───────────┘
               │
               ▼
     ┌──────────────────────────┐
     │ checkForUpgrade()        │
     │ Query Server             │
     └─────────┬────────────────┘
               │
     ┌─────────┴──────────┐
     │                    │
Server has            Server version
newer version         = Current
     │                    │
     ▼                    ▼
┌──────────┐         ┌─────────┐
│  Show    │         │ No      │
│ Upgrade  │         │ Action  │
│ Dialog   │         └─────────┘
└────┬─────┘
     │
User clicks "Update"
     │
     ▼
┌────────────────────────┐
│ Download from Server:  │
│ http://{host}/mm/{id}  │
└──────────┬─────────────┘
           │
           ▼
┌──────────────────────┐
│ Android Installer    │
│ Opens                │
└──────────┬───────────┘
           │
           ▼
┌────────────────────────┐
│ Mini → Full (newer)    │
│ Full → Full (newer)    │
└────────────────────────┘


Posting Restriction Flow:
┌──────────────────────────┐
│ User Tries to Post       │
│ (Mini, > 5 tweets)       │
└──────────┬───────────────┘
           │
           ▼
┌──────────────────────────┐
│ Show Upgrade Dialog      │
└──────────┬───────────────┘
           │
User clicks "Upgrade Now"
           │
           ▼
┌──────────────────────────┐
│ checkForUpgrade()        │
│ (Same as automatic)      │
└──────────────────────────┘
```

## Components

### Server API
**File**: `HproseInstance.kt`

```kotlin
suspend fun checkUpgrade(): Map<String, String>? {
    val entry = "check_upgrade"
    val params = mapOf(
        "aid" to appId,
        "ver" to "last",
        "entry" to entry
    )
    return appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
        ?.mapValues { it.value.toString() }
}
```

### Upgrade Check Logic
**File**: `TweetActivity.kt` → `ActivityViewModel`

```kotlin
fun checkForUpgrade(context: Context) {
    viewModelScope.launch(IO) {
        delay(15000)
        val versionInfo = HproseInstance.checkUpgrade() ?: return@launch
        val currentVersion = versionName.replace("-mini", "").toIntOrNull() ?: return@launch
        val serverVersion = versionInfo["version"]?.toIntOrNull() ?: return@launch
        
        if (currentVersion < serverVersion) {
            val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
            showUpdateDialog(context, downloadUrl)
        }
    }
}
```

### Posting Restriction
**File**: `ComposeTweetScreen.kt`

```kotlin
val onSendClick = {
    // Mini version posting restriction
    if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
        showUpgradeRequiredDialog = true
    }
    // Full version node requirement
    else if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && 
             appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
        showNodeRequiredDialog = true
    }
    // Allow posting
    else {
        tweetFeedViewModel.uploadTweet(...)
    }
}

// Upgrade dialog
if (showUpgradeRequiredDialog) {
    AlertDialog(
        confirmButton = {
            Button(onClick = {
                activityViewModel.checkForUpgrade(context)
            })
        }
    )
}
```

## Benefits

✅ **Single Source of Truth**: Server is authoritative
✅ **No Redundancy**: One upgrade mechanism, not two
✅ **Consistency**: All users get same package
✅ **Simplicity**: Less code, easier to maintain
✅ **Server Control**: Push upgrades when ready
✅ **Unified Experience**: Mini and full treated identically

## What Was Removed

❌ **UpgradeManager** - Background download manager
❌ **ApkDownloadWorker** - Background download worker
❌ **UpgradeBanner** - Optional upgrade banner component
❌ **BuildConfig.FULL_APK_URL** - Hardcoded APK URL
❌ **Background download initiation** - TweetActivity startup
❌ **Local APK installation** - FileProvider setup for local APK

## Build Commands

```bash
# Mini version (10 MB, no FFmpeg)
./gradlew assembleMiniRelease

# Full version (54 MB, with FFmpeg)
./gradlew assembleFullRelease
```

## Testing

### Test Automatic Upgrade
```bash
# Install any version
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Wait 15 seconds after app starts
# Monitor logs
adb logcat | grep checkForUpgrade

# Expected: Dialog if server has newer version
```

### Test Posting Restriction
```bash
# Install mini version
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Log in as non-guest user with > 5 tweets
# Try to post a new tweet

# Expected: Upgrade dialog appears
# Click "Upgrade Now"
# Expected: Calls checkForUpgrade(), downloads from server
```

## Server Requirements

### API Endpoint
```
HproseInstance.checkUpgrade()
```

### Response Format
```json
{
    "version": "39",
    "packageId": "abc123def456..."
}
```

### Package Storage
```
http://{hostIp}/mm/{packageId}
```

Must serve: Full version APK file

## Deployment

1. **Build full version**:
```bash
./gradlew assembleFullRelease
```

2. **Upload to server** with version number:
```
Version: 39
PackageId: {mimei_id}
```

3. **Server provides** via `checkUpgrade()`

4. **All installations notified**:
   - Mini users: Upgrade to full
   - Full users: Upgrade to newer full

## Localization

**Chinese & Japanese only**

### Upgrade Dialog
- **Title**: "更新可用" / "アップデート利用可能"
- **Message**: "有新版本可用" / "新しいバージョンが利用可能です"
- **Buttons**: "更新" / "更新" | "取消" / "キャンセル"

### Posting Restriction (Mini)
- **Title**: "需要升級" / "アップグレードが必要です"
- **Message**: "您已發布超過5條推文，需要升級..." / "新しいツイートを投稿するには..."
- **Buttons**: "立即升級" / "今すぐアップグレード" | "取消" / "キャンセル"

### Node Alert (Full)
- **Title**: "需要建立自己的節點" / "独自ノードの設定が必要です"
- **Message**: "您已發布超過10條推文..." / "新しいツイートを投稿するには..."
- **Button**: "我知道了" / "わかりました"

## Related Documentation

- 📖 **Server Upgrade System**: `docs/SERVER_UPGRADE_SYSTEM.md`
- 📚 **Posting Restrictions**: `docs/POSTING_RESTRICTIONS.md`
- 🔧 **Build Configuration**: `docs/BUILD_FLAVORS.md`

---

**Last Updated**: October 14, 2025
**Architecture**: Single server-driven upgrade system
**Removed**: Background download mechanism (redundant)

