# Debug Upgrade Issue

## Current Situation

- **Server APK**: versionCode 68
- **Mini App**: versionCode 67
- **Manual install**: Works ✅ (68 overwrites 67)
- **App upgrade feature**: Refuses ❌ ("version is up to date")

## Why Manual Works But App Refuses?

The comparison `68 > 67` should return `true`, so there must be something else going on.

## Debug Steps

### 1. Capture Logs During Upgrade Attempt

Run this command, then trigger upgrade in mini app:

```bash
adb logcat -c  # Clear logs
adb logcat | grep -E "checkForUpgrade|Downloaded APK versionCode|APK versionCode"
```

Then in mini app:
1. Click compose button
2. Click "立即升級"
3. Watch the logs

### 2. Look For These Log Lines

```
checkForUpgrade: Mini user requesting upgrade: 38-mini (code=67)
checkForUpgrade: Starting download to verify versionCode: http://...
checkForUpgrade: Downloaded APK versionCode: XX, current: 67
checkForUpgrade: APK versionCode (XX) > current (67), installing
```

OR

```
checkForUpgrade: APK versionCode (XX) not higher than current (67), deleting file
```

### 3. Possible Issues

**Issue A: Mini app thinks it's version 68**
- Check installed mini app versionCode
- Maybe you're running the full version?

**Issue B: Downloaded APK has versionCode 67**
- Server is serving old APK
- Check server's APK file

**Issue C: APK can't be read**
- File path issue
- Permissions issue

## Check Current Installed App

```bash
# What's actually installed?
adb shell dumpsys package us.fireshare.tweet | grep -E "versionCode|versionName"
```

This will show the ACTUAL installed app's version.

## Check Downloaded APK Location

The app downloads to:
```
/sdcard/Download/fireshare.apk
```

You can pull it and check:
```bash
adb pull /sdcard/Download/fireshare.apk /tmp/
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging /tmp/fireshare.apk | grep versionCode
```

## Questions to Answer

1. **What versionCode is the installed mini app?**
   ```bash
   adb shell dumpsys package us.fireshare.tweet | grep versionCode
   ```

2. **What does the log say when you click upgrade?**
   ```bash
   adb logcat | grep checkForUpgrade
   ```

3. **What versionCode is the APK on the server?**
   - Check the APK file you uploaded to server

---

**Next**: Run these commands and share the output to debug the issue

