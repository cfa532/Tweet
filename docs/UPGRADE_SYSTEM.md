# App Upgrade System

## Overview

The app implements a sophisticated upgrade system that supports:
1. **Mini to Full Version Upgrade** - Background download and installation
2. **Tweet Count Requirements** - Automatic upgrade enforcement based on user activity
3. **Node Requirements** - Alerts for users needing their own infrastructure

## How User Requests to Upgrade to Full Version

### Automatic Upgrade Flow

The mini version automatically handles upgrades without user intervention:

#### 1. **Background Download (Automatic)**
When the app starts:
- `TweetActivity` onCreate detects mini version
- `UpgradeManager.startDownload()` is called automatically
- `ApkDownloadWorker` downloads full APK (~54 MB) in background
- Progress notification shows download status
- Download happens silently while user uses the app

#### 2. **Upgrade Banner (Optional)**
Once download completes:
- Banner appears on `TweetFeedScreen`
- Shows "完整版已準備好！" (Chinese) or "完全版の準備ができました！" (Japanese)
- User can click "立即升級" (Upgrade Now) button
- Banner can be dismissed if user wants to wait

#### 3. **User Taps Upgrade Button**
When user taps the upgrade button:
- `UpgradeManager.installFullVersion()` is called
- Uses `FileProvider` to share APK file
- Android package installer opens with full APK
- User confirms installation (standard Android flow)
- Full version replaces mini version automatically
- User data and settings are preserved

### Posting Restriction (> 5 Tweets)

When mini version user has more than 5 tweets:

#### Trigger Condition
```kotlin
// Checked when user tries to post a new tweet
if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5)
```

#### Behavior
- **User can use app normally** - viewing, liking, commenting all work
- **Posting blocked** - When user tries to compose new tweet, dialog appears
- **Dialog is dismissable** - User can cancel and keep editing
- Dialog shows upgrade requirement and current download status
- "今すぐアップグレード" button enabled when download completes
- Content is preserved when dialog is dismissed

#### Localized Messages
**Chinese:**
- Title: "需要升級"
- Message: "您已發布超過5條推文，需要升級到完整版才能繼續發布。完整版可以離線處理視頻並提供更多功能。"
- Buttons: "立即升級" (if ready) or "下載中..." (if downloading) / "取消"

**Japanese:**
- Title: "アップグレードが必要です"
- Message: "新しいツイートを投稿するには、完全版へのアップグレードが必要です（5つ以上のツイートがあります）。完全版では、オフラインで動画を処理し、より多くの機能を利用できます。"
- Buttons: "今すぐアップグレード" (if ready) or "ダウンロード中..." (if downloading) / "キャンセル"

## Node Requirement Posting Restriction (Full Version)

For full version users with heavy usage:

### Trigger Condition
```kotlin
// Checked when user tries to post a new tweet
if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0)
```

### Behavior
- **User can use app normally** - viewing, liking, commenting all work
- **Posting blocked** - When user tries to compose new tweet, dialog appears
- **Dialog is dismissable** - User can cancel and keep editing
- Reminds user to establish their own node
- Content is preserved when dialog is dismissed
- Shows when user has significant content but no dedicated infrastructure

### Localized Messages
**Chinese:**
- Title: "需要建立自己的節點"
- Message: "您已發布超過10條推文，需要建立自己的節點才能繼續發布。請建立您自己的節點以獲得更好的性能和存儲。"
- Button: "我知道了"

**Japanese:**
- Title: "独自ノードの設定が必要です"
- Message: "新しいツイートを投稿するには、独自のノードを設定する必要があります（10を超えるツイートがあります）。より良いパフォーマンスとストレージのために、独自のノードを確立してください。"
- Button: "わかりました"

## Technical Implementation

### Components

#### 1. UpgradeManager (Singleton)
```kotlin
@Singleton
class UpgradeManager @Inject constructor(@ApplicationContext context: Context)
```

**Key Methods:**
- `isMiniVersion(): Boolean` - Check if app is mini version
- `startDownload()` - Initiate background APK download
- `isDownloadCompleted(): Boolean` - Check download status
- `installFullVersion(): Boolean` - Launch Android package installer
- `getDownloadProgress(): Int` - Get download progress (0-100)

**SharedPreferences:**
- `apk_download.download_completed` - Download completion status
- `apk_download.download_initiated` - Download initiation status

#### 2. ApkDownloadWorker (Hilt Worker)
```kotlin
@HiltWorker
class ApkDownloadWorker @AssistedInject constructor(...)
```

**Features:**
- Background download using WorkManager
- Foreground service notification during download
- Progress updates every 5%
- Automatic retry on failure
- Cleanup on failed downloads

**Notifications:**
- **Download Progress**: "Downloading full version... X% complete"
- **Download Complete**: "Full version ready! Tap to upgrade..."

#### 3. UpgradeBanner (Composable)
```kotlin
@Composable
fun UpgradeBanner(upgradeManager: UpgradeManager, modifier: Modifier)
```

**States:**
- Downloading: Shows progress indicator
- Ready: Shows upgrade button
- Forced: Shows non-dismissable dialog (> 5 tweets)
- Node Alert: Shows node requirement alert (full version, > 10 tweets)

**UI Elements:**
- Animated slide-in/fade-in appearance
- Material 3 design
- Dismissable close button (optional upgrade only)
- Primary action button for upgrade

### Integration Points

#### TweetActivity
```kotlin
// Start download on app launch (mini version only)
if (BuildConfig.IS_MINI_VERSION) {
    upgradeManager.startDownload()
}
```

#### TweetFeedScreen
```kotlin
// Show optional upgrade banner on main feed
UpgradeBanner(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
)
```

#### ComposeTweetScreen
```kotlin
// Check posting restrictions when user tries to send tweet
val onSendClick = {
    if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
        // Mini version: Block if > 5 tweets
        if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
            showUpgradeRequiredDialog = true
        }
        // Full version: Block if > 10 tweets and no node
        else if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
            showNodeRequiredDialog = true
        }
        else {
            // Post tweet
            tweetFeedViewModel.uploadTweet(...)
        }
    }
}
```

### FileProvider Configuration

**path_provider.xml:**
```xml
<external-files-path name="apk" path="apk" />
```

**AndroidManifest.xml:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/path_provider" />
</provider>
```

## Build Configuration

### Mini Version
```kotlin
create("mini") {
    dimension = "version"
    versionNameSuffix = "-mini"
    buildConfigField("Boolean", "IS_MINI_VERSION", "true")
    buildConfigField("String", "FULL_APK_URL", "\"https://tweet.fireshare.uk/app/full/app-full-release.apk\"")
}
```

### Full Version
```kotlin
create("full") {
    dimension = "version"
    versionNameSuffix = ""
    buildConfigField("Boolean", "IS_MINI_VERSION", "false")
    buildConfigField("String", "FULL_APK_URL", "\"\"")
}
```

## User Experience Flow

### First-Time User (Mini Version)

1. **Download App** - User downloads 10 MB mini version from distribution
2. **Launch App** - App starts normally, all features work immediately
3. **Background Download** - Full version downloads silently in background
4. **Use App** - User posts tweets, browses feed, videos upload via backend
5. **Banner Appears** - Once download completes, optional upgrade banner appears
6. **Optional Upgrade** - User can upgrade when convenient or dismiss banner
7. **Continue Posting** - User can post up to 5 tweets without any restrictions
8. **Posting Blocked** - After 5 tweets, posting is blocked until upgrade (viewing/liking still work)
9. **Upgrade Dialog** - When trying to post 6th tweet, upgrade dialog appears
10. **Install Full** - User taps upgrade, Android installer opens, installation completes
11. **Full Version** - App restarts with full version, FFmpeg now available, unlimited posting

### Existing User (Full Version)

1. **Heavy Usage** - User posts > 10 tweets
2. **No Node** - User has not set up cloudDrivePort (value is 0)
3. **Continue Using** - User can browse, like, comment normally
4. **Posting Blocked** - When trying to post 11th tweet, node setup dialog appears
5. **Dialog Message** - Explains need to set up private node
6. **Dismiss** - User can dismiss and continue using app (but cannot post)
7. **Set Up Node** - User configures cloudDrivePort
8. **Posting Enabled** - Once node is set up, posting works again

## Security Considerations

### APK Verification
- APK served from trusted domain: `tweet.fireshare.uk`
- HTTPS connection for download
- FileProvider prevents direct file access
- Android verifies signature during installation

### Permissions
- No special permissions required for download
- Android system handles installation permissions
- User must explicitly approve installation

## Monitoring and Debugging

### Logs
```bash
# Monitor upgrade flow
adb logcat | grep -E "UpgradeManager|ApkDownloadWorker|UpgradeBanner"

# Check download status
adb logcat | grep "APK download"

# Monitor installation
adb logcat | grep "installFullVersion"
```

### Testing Commands

```bash
# Install mini version
./gradlew assembleMiniDebug
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Install full version
./gradlew assembleFullDebug
adb install app/build/outputs/apk/full/debug/app-full-debug.apk

# Clear app data to test fresh install
adb shell pm clear us.fireshare.tweet.debug
```

### SharedPreferences Inspection
```bash
# Check download status
adb shell run-as us.fireshare.tweet.debug cat /data/data/us.fireshare.tweet.debug/shared_prefs/apk_download.xml
```

## Deployment Checklist

### Building and Signing

1. **Build Mini Version**
```bash
./gradlew assembleMiniRelease
```

2. **Build Full Version**
```bash
./gradlew assembleFullRelease
```

3. **Sign APKs**
```bash
jarsigner -keystore your-keystore.jks \
  app/build/outputs/apk/mini/release/app-mini-release.apk your-alias

jarsigner -keystore your-keystore.jks \
  app/build/outputs/apk/full/release/app-full-release.apk your-alias
```

4. **Upload Full Version**
Upload to: `https://tweet.fireshare.uk/app/full/app-full-release.apk`

5. **Distribute Mini Version**
- Upload to Play Store
- Or distribute via web/direct download

### Server Requirements

**Full APK Hosting:**
- URL: `https://tweet.fireshare.uk/app/full/app-full-release.apk`
- Content-Type: `application/vnd.android.package-archive`
- Size: ~54 MB
- HTTPS required
- CORS headers not required (direct download)

## Troubleshooting

### Download Not Starting
- Check `BuildConfig.IS_MINI_VERSION` is true
- Verify network connectivity
- Check WorkManager status: `adb shell dumpsys jobscheduler | grep ApkDownloadWorker`

### Download Fails
- Verify `FULL_APK_URL` is accessible
- Check network connectivity
- Worker will automatically retry

### Installation Fails
- Check FileProvider configuration in AndroidManifest.xml
- Verify path_provider.xml includes apk path
- Check APK file exists in external files directory

### Banner Not Showing
- Verify mini version: `BuildConfig.IS_MINI_VERSION`
- Check download status: `UpgradeManager.isDownloadCompleted()`
- Look for logs: `adb logcat | grep UpgradeBanner`

## Benefits

✅ **Better First Install Experience** - 10 MB vs 54 MB (81% smaller)
✅ **Immediate Functionality** - All features work on mini version via backend
✅ **Transparent Upgrade** - Background download, no user interruption
✅ **Non-Intrusive** - Restrictions only block posting, not app usage
✅ **Progressive Enhancement** - Start with backend, upgrade to local processing
✅ **Usage-Based Requirements** - Posting restrictions only when needed (> 5 or > 10 tweets)
✅ **Infrastructure Awareness** - Ensures heavy users have dedicated resources
✅ **Content Preserved** - No lost work when restriction dialogs appear

---

**Last Updated**: October 14, 2025
**Related Documentation**: `docs/BUILD_FLAVORS.md`, `docs/MINI_VERSION_IMPLEMENTATION.md`

