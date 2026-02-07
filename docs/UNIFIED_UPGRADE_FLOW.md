# Unified Upgrade Flow

## Overview

The app uses a **unified upgrade mechanism** where both mini and full version users receive upgrades from the **same server package**. When the server's versionName is **larger than** the app's versionName, a new full version upgrade is available. This ensures consistency and allows mini users to seamlessly upgrade to full version using the authoritative server source.

**Note**: The **Play variant is excluded** from this server upgrade mechanism and is upgraded through **Google Play Store** instead.

## Key Principle

**Upgrade availability rule**: When server versionName > app versionName, a new full version upgrade is available.

**All upgrade requests use the server package from `checkForUpgrade()`** (mini and full versions only):
- ✅ Automatic upgrade check (15s after app start)
- ✅ Posting restriction upgrade (mini version, > 5 tweets)
- ✅ Manual upgrade from banner (mini version)
- ❌ **Play variant excluded**: Upgraded through Google Play Store, not server upgrade

## Upgrade Triggers

### 1. Automatic Server Check
**When**: 15 seconds after app launch
**Who**: Mini and Full version users (Play variant excluded)
**How**: `ActivityViewModel.checkForUpgrade()`
**Condition**: Shows upgrade dialog when server versionName > app versionName

```kotlin
// In TweetActivity.kt
launch {
    delay(15000)
    activityViewModel.checkForUpgrade(this@TweetActivity)
}
```

**Behavior**:
- Queries server for latest version
- Compares with current version (strips "-mini" suffix)
- Shows dialog if newer version available
- Downloads from server: `http://{hostIp}/mm/{packageId}`

### 2. Posting Restriction (Mini Version)
**When**: User with > 5 tweets tries to post
**Who**: Mini version non-guest users only
**How**: `ComposeTweetScreen` upgrade dialog

```kotlin
// When user clicks send with > 5 tweets
if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
    showUpgradeRequiredDialog = true
}
```

**Behavior**:
- Blocks posting new tweets
- Shows upgrade dialog
- User clicks "Upgrade Now"
- **Triggers server upgrade check** → Uses same package as automatic check
- Also attempts local upgrade if background download complete (fallback)

### 3. Optional Banner (Mini Version)
**When**: Background download completes
**Who**: Mini version users only
**How**: `UpgradeBanner` component

```kotlin
// In TweetFeedScreen
UpgradeBanner(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
)
```

**Behavior**:
- Shows when background APK download completes
- User can dismiss (optional)
- User clicks "Upgrade Now"
- Installs from background-downloaded APK

## Upgrade Flow Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    App Launch (All)                     │
└────────────┬────────────────────────────────────────────┘
             │
             ├─────────────► Wait 15s ─────────────────┐
             │                                          │
             ├─► Mini: Start Background Download       │
             │   (Fallback APK)                         │
             │                                          ▼
             │                              ┌────────────────────┐
             │                              │ checkForUpgrade()  │
             │                              │ Query Server       │
             │                              └─────────┬──────────┘
             │                                        │
             │                          ┌─────────────┴──────────────┐
             │                          │                            │
             │                    Server has          Server version = Current
             │                    newer version                      │
             │                          │                            │
             │                          ▼                            ▼
             │                   ┌─────────────┐            ┌──────────────┐
             │                   │Show Upgrade │            │Update Entry  │
             │                   │Dialog       │            │URLs          │
             │                   └──────┬──────┘            └──────────────┘
             │                          │
             │                   User clicks "Update"
             │                          │
             │                          ▼
             │              ┌────────────────────────┐
             │              │Download from Server:   │
             │              │http://{host}/mm/{id}   │
             │              └────────┬───────────────┘
             │                       │
             │                       ▼
             │              ┌────────────────────────┐
             │              │Android Package         │
             │              │Installer Opens         │
             │              └────────┬───────────────┘
             │                       │
             │                       ▼
             │              ┌────────────────────────┐
             │              │Mini → Full v39         │
             │              │Full v38 → Full v39     │
             │              └────────────────────────┘
             │
             ├─► Mini User Posts Tweet
             │   (> 5 tweets)
             │        │
             │        ▼
             │   ┌────────────────┐
             │   │Block Posting   │
             │   │Show Dialog     │
             │   └────────┬───────┘
             │            │
             │     User clicks "Upgrade Now"
             │            │
             │            ├─► checkForUpgrade() ──────┐
             │            │   (Uses server package)    │
             │            │                            │
             │            └─► installFullVersion() ────┤
             │                (Fallback if local       │
             │                 download complete)      │
             │                                         │
             └─────────────────────────────────────────┘
                   (Both paths use server package
                    when available)
```

## Implementation Details

### Server Upgrade (Primary)

**File**: `TweetActivity.kt` → `ActivityViewModel.checkForUpgrade()`

```kotlin
fun checkForUpgrade(context: Context) {
    viewModelScope.launch(IO) {
        delay(15000)
        val versionInfo = HproseInstance.checkUpgrade()
        val currentVersion = versionName.replace("-mini", "").toInt()
        val serverVersion = versionInfo["version"]?.toInt()
        
        if (currentVersion < serverVersion) {
            // Server package URL (same for mini and full)
            val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
            showUpdateDialog(context, downloadUrl)
        }
    }
}
```

### Posting Restriction Upgrade (Uses Server)

**File**: `ComposeTweetScreen.kt`

```kotlin
// When user with > 5 tweets clicks send
if (showUpgradeRequiredDialog) {
    AlertDialog(
        confirmButton = {
            Button(onClick = {
                // PRIMARY: Trigger server upgrade check
                // This uses the same package as automatic check
                activityViewModel.checkForUpgrade(context)
                
                // FALLBACK: Use local download if available
                if (upgradeManager.isDownloadCompleted()) {
                    upgradeManager.installFullVersion()
                }
            })
        }
    )
}
```

### Background Download (Fallback)

**File**: `UpgradeManager.kt` / `ApkDownloadWorker.kt`

```kotlin
// Downloads from hardcoded URL as fallback
val url = BuildConfig.FULL_APK_URL
// "https://tweet.fireshare.uk/app/full/app-full-release.apk"
```

## Priority Order

When user triggers upgrade, the system tries:

1. **Server package** (via `checkForUpgrade()`)
   - Authoritative source
   - Always has latest version
   - Same for all users
   
2. **Local download** (if background download complete)
   - Fallback mechanism
   - May not be latest version
   - Mini version only

## Benefits

### Unified Package Source
✅ **Consistency**: All users get same package from server
✅ **Up-to-date**: Server always has latest version
✅ **Control**: Server decides when to push upgrades
✅ **Reliability**: Single authoritative source

### Multiple Trigger Points
✅ **Proactive**: Automatic check 15s after launch
✅ **Enforced**: Posting blocked after 5 tweets (mini)
✅ **Optional**: Banner when background download complete

### Graceful Fallback
✅ **Redundancy**: Background download as backup
✅ **Offline**: Can upgrade without server (if local download complete)
✅ **Flexible**: Server or local, whichever available

## Version Tracking

### Mini Version
- **versionName**: `"38-mini"`
- **Server check**: Strips suffix → `38`
- **Receives**: Full version from server
- **After upgrade**: Becomes full version

### Full Version
- **versionName**: `"38"`
- **Server check**: Direct comparison → `38`
- **Receives**: Newer full version from server
- **After upgrade**: Still full version, newer

## User Experience

### Scenario 1: Automatic Upgrade (All Users)
```
App Launch → Wait 15s → Server Check → Dialog → Download → Install
```

### Scenario 2: Posting Restriction (Mini, > 5 tweets)
```
Try to Post → Blocked → Dialog → Click "Upgrade" → Server Check → Download → Install
```

### Scenario 3: Optional Banner (Mini)
```
App Launch → Background Download → Banner Appears → Click "Upgrade" → Install Local
```

## Code Integration

### TweetActivity
```kotlin
@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private val activityViewModel: ActivityViewModel by viewModels()
    
    @Inject
    lateinit var upgradeManager: UpgradeManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            // 1. Server upgrade check (all users)
            activityViewModel.checkForUpgrade(this@TweetActivity)
            
            // 2. Background download (mini only, fallback)
            if (BuildConfig.IS_MINI_VERSION) {
                upgradeManager.startDownload()
            }
        }
    }
}
```

### ComposeTweetScreen
```kotlin
@Composable
fun ComposeTweetScreen(
    activityViewModel: ActivityViewModel = viewModel()
) {
    val onSendClick = {
        if (BuildConfig.IS_MINI_VERSION && appUser.tweetCount > 5) {
            showUpgradeRequiredDialog = true
        } else {
            // Post tweet
        }
    }
    
    if (showUpgradeRequiredDialog) {
        AlertDialog(
            confirmButton = {
                // Use server package (same as automatic check)
                activityViewModel.checkForUpgrade(context)
                
                // Fallback to local if available
                if (upgradeManager.isDownloadCompleted()) {
                    upgradeManager.installFullVersion()
                }
            }
        )
    }
}
```

## Testing

### Test Server Upgrade
```bash
# All users should get upgrade from server
adb logcat | grep -E "checkForUpgrade|Update available"

# Expected: Dialog after 15s if server has newer version
```

### Test Posting Restriction Upgrade
```bash
# Mini user with 6 tweets tries to post
# Expected: Upgrade dialog
# Expected: Calls checkForUpgrade() when clicked
# Expected: Downloads from server package
```

### Test Background Download
```bash
# Mini version only
# Expected: Background download starts
# Expected: Banner appears when complete
# Expected: Can install from local APK
```

## Server Requirements

### Version Info API
```
HproseInstance.checkUpgrade()
```

**Returns**:
```kotlin
{
    "version": "39",
    "packageId": "abc123..."
}
```

### Package Storage
```
http://{hostIp}/mm/{packageId}
```

**Must serve**: Full version APK for both mini and full users

## Related Documentation

- 📖 **Server Upgrade System**: `docs/SERVER_UPGRADE_SYSTEM.md`
- 🏗️ **Upgrade System**: `docs/UPGRADE_SYSTEM.md`
- 📚 **Posting Restrictions**: `docs/POSTING_RESTRICTIONS.md`
- 🔧 **Build Configuration**: `docs/BUILD_FLAVORS.md`

---

**Last Updated**: October 14, 2025
**Key Principle**: All upgrades use server package when available

