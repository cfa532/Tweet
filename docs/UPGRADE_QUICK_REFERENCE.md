# Upgrade System - Quick Reference

## How Users Upgrade from Mini to Full Version

### Automatic Process
1. **User downloads mini version** (10 MB)
2. **App auto-downloads full version in background** (~54 MB)
3. **Upgrade banner appears** when download completes
4. **User taps "立即升級"** (Upgrade Now)
5. **Android installer opens**, user confirms
6. **Full version installed**, app restarts with FFmpeg

### Forced Upgrade (> 5 Tweets)
- **Non-guest users with > 5 tweets** must upgrade
- **Non-dismissable dialog** appears
- User must wait for download to complete
- Tap "今すぐアップグレード" to install

### Node Requirement Alert (Full Version)
- **Users with > 10 tweets** and **no cloudDrivePort** get alert
- Reminds to establish own node
- Can be dismissed (just a reminder)

## Build Commands

```bash
# Mini version (10 MB, no FFmpeg)
./gradlew assembleMiniRelease

# Full version (54 MB, with FFmpeg)
./gradlew assembleFullRelease
```

## Key Components

| Component | Purpose |
|-----------|---------|
| `UpgradeManager` | Manages download and installation |
| `ApkDownloadWorker` | Background download service |
| `UpgradeBanner` | UI component with upgrade flow |
| `TweetActivity` | Initiates download on app start |
| `TweetFeedScreen` | Shows upgrade banner/alerts |

## Configuration

**Mini Version:**
- `IS_MINI_VERSION = true`
- `FULL_APK_URL = "https://tweet.fireshare.uk/app/full/app-full-release.apk"`
- No FFmpeg dependencies

**Full Version:**
- `IS_MINI_VERSION = false`
- `FULL_APK_URL = ""`
- Includes FFmpeg dependencies

## User Experience

### Mini Version
- ✅ All features work immediately (backend video processing)
- ✅ Smaller download (10 MB)
- ✅ Optional upgrade (until 5 tweets)
- ⚠️ Forced upgrade at 5 tweets
- 📡 Requires internet for video processing

### Full Version
- ✅ Offline video processing with FFmpeg
- ✅ No forced upgrades
- ✅ Full features
- ⚠️ Alert at 10 tweets if no node
- 💾 54 MB download

## Localization

**Languages: Chinese (Traditional) and Japanese only**

### Chinese
- 完整版已準備好！ (Full version ready!)
- 立即升級 (Upgrade now)
- 需要升級 (Upgrade required)
- 需要建立自己的節點 (Need to establish own node)

### Japanese
- 完全版の準備ができました！ (Full version ready!)
- 今すぐアップグレード (Upgrade now)
- アップグレードが必要です (Upgrade required)
- 独自ノードの設定が必要です (Need to set up own node)

## Testing

```bash
# Test mini version
./gradlew assembleMiniDebug
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk

# Monitor logs
adb logcat | grep -E "UpgradeManager|ApkDownloadWorker"

# Test with > 5 tweets to see forced upgrade
# Test with > 10 tweets and cloudDrivePort=0 to see node alert
```

## Deployment

1. Build both versions
2. Sign APKs
3. Upload full version to: `https://tweet.fireshare.uk/app/full/app-full-release.apk`
4. Distribute mini version to users
5. Users auto-upgrade as needed

---

📖 **Full Documentation**: `docs/UPGRADE_SYSTEM.md`  
🏗️ **Build Configuration**: `docs/BUILD_FLAVORS.md`

