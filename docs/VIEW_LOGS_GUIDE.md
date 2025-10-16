# How to View Upgrade Logs

## The Issue

Mini (versionCode 67) should upgrade to server (versionCode 68), but shows "version is up to date".

## View Logs to Debug

### Method 1: Android Studio Logcat

1. Open Android Studio
2. Click **Logcat** tab at the bottom
3. In the filter box, type: `checkForUpgrade`
4. Click compose button in mini app
5. Click "立即升級"
6. Watch the logs appear

### Method 2: Command Line (if adb in PATH)

```bash
# Add adb to PATH first
export PATH=$PATH:~/Library/Android/sdk/platform-tools

# Clear logs and monitor
adb logcat -c
adb logcat | grep checkForUpgrade
```

Then trigger upgrade in app.

### What to Look For

The logs should show:

```
✅ Good upgrade flow:
checkForUpgrade: Mini user requesting upgrade: 38-mini (code=67)
checkForUpgrade: Starting download to verify versionCode: http://...
checkForUpgrade: APK file exists at: /sdcard/Download/fireshare.apk, size: 56000000 bytes
checkForUpgrade: Downloaded APK - versionCode: 68, versionName: 38, current: 67
checkForUpgrade: APK versionCode (68) > current (67), installing
```

```
❌ Bad flow (what you're seeing):
checkForUpgrade: Mini user requesting upgrade: 38-mini (code=67)
checkForUpgrade: Starting download to verify versionCode: http://...
checkForUpgrade: APK file exists at: /sdcard/Download/fireshare.apk, size: XXX bytes
checkForUpgrade: Downloaded APK - versionCode: 67 (or 68?), versionName: XX, current: 67
checkForUpgrade: APK versionCode (67) not higher than current (67), deleting file
Toast: 沒有可用的升級
```

## Key Information Needed

From the logs, we need to see:
1. **Downloaded APK versionCode**: What number?
2. **Current versionCode**: Should be 67
3. **Comparison result**: XX > 67?

## Improved Logging

I've added better logging to show:
- APK file path and size
- Whether getPackageArchiveInfo succeeds
- Exact versionCode and versionName from APK
- Detailed comparison

## Next Steps

1. **Rebuild mini** with new logging:
   ```bash
   cd /Users/cfa532/Documents/GitHub/Tweet
   ./gradlew assembleMiniRelease
   ```

2. **Install on device** from Android Studio or:
   ```bash
   ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/mini/release/app-mini-release.apk
   ```

3. **View logs** (Android Studio Logcat or command line)

4. **Try upgrade** and share what the logs show

---

**The logs will tell us exactly why the upgrade is being rejected!**

