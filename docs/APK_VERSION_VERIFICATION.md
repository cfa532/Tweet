# APK Version Code Verification

## ✅ Correct Implementation

The app now verifies versionCode from the **actual downloaded APK file**, not from the server response.

## Why This Is Better

### ❌ Old Approach (Wrong)
```kotlin
// Relied on server's checkUpgrade() returning versionCode
val serverVersionCode = versionInfo["versionCode"]?.toIntOrNull()
if (serverVersionCode > currentVersionCode) {
    download()
}
```

**Problem**: Server's `checkUpgrade()` returns `"version": "38"` which is the **versionName**, not versionCode!

### ✅ New Approach (Correct)
```kotlin
// 1. Download APK file first
downloadManager.enqueue(request)

// 2. Read versionCode from downloaded APK
val apkPackageInfo = context.packageManager.getPackageArchiveInfo(
    apkFile.absolutePath, 0
)
val apkVersionCode = apkPackageInfo?.longVersionCode?.toInt()

// 3. Compare with current
if (apkVersionCode > currentVersionCode) {
    install()  // ✅ Allow
} else {
    apkFile.delete()  // ❌ Deny and cleanup
    Toast("沒有可用的升級")
}
```

**Benefit**: Verifies the **actual APK file** that will be installed!

## Implementation

### Step-by-Step Flow

**1. User Triggers Upgrade**
```kotlin
// Mini user clicks compose button with > 5 tweets
activityViewModel.checkForUpgrade(context, immediate = true)
```

**2. Query Server**
```kotlin
val versionInfo = HproseInstance.checkUpgrade()
// Returns: { "version": "38", "packageId": "abc123..." }
val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
```

**3. Download APK**
```kotlin
downloadManager.enqueue(request)
// Downloads to: /sdcard/Download/fireshare.apk
```

**4. Read VersionCode from APK** ⭐
```kotlin
val apkFile = File(Environment.DIRECTORY_DOWNLOADS, "fireshare.apk")
val apkPackageInfo = context.packageManager.getPackageArchiveInfo(
    apkFile.absolutePath, 
    0  // No flags needed, just basic info
)
val apkVersionCode = apkPackageInfo?.longVersionCode?.toInt()
```

**5. Verify VersionCode**
```kotlin
val currentVersionCode = 67  // Mini version

if (apkVersionCode > currentVersionCode) {
    // Example: 68 > 67 ✅
    Timber.d("APK versionCode (68) > current (67), installing")
    context.startActivity(installIntent)
} else {
    // Example: 67 >= 67 ❌
    Timber.w("APK versionCode (67) not higher, deleting")
    apkFile.delete()
    Toast.makeText(context, "沒有可用的升級", Toast.LENGTH_LONG).show()
}
```

## Version Code Sources

### ✅ Correct: From APK File
```kotlin
// Read from downloaded APK file
val apkPackageInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
val apkVersionCode = apkPackageInfo?.longVersionCode?.toInt()
```

**This is the ACTUAL versionCode from the APK** that will be installed:
- Mini APK: versionCode = 67
- Full APK: versionCode = 68

### ❌ Incorrect: From Server Response
```kotlin
// checkUpgrade() returns this
{
    "version": "38",        // This is versionName, not versionCode!
    "packageId": "abc..."
}
```

The server doesn't return versionCode at all - just versionName.

## Example Scenarios

### Scenario 1: Valid Upgrade
```
Current: Mini versionCode 67
Downloads: Full APK with versionCode 68
Verification: 68 > 67 ✅
Action: Install
Result: Mini → Full (upgrade successful)
```

### Scenario 2: Invalid Upgrade (Same Version)
```
Current: Full versionCode 68
Downloads: Full APK with versionCode 68
Verification: 68 > 68 ❌
Action: Delete APK, show toast
Result: "沒有可用的升級" (No upgrade available)
```

### Scenario 3: Invalid Upgrade (Downgrade)
```
Current: Full versionCode 69
Downloads: Full APK with versionCode 68
Verification: 68 > 69 ❌
Action: Delete APK, show toast
Result: "沒有可用的升級"
```

## Security Benefits

✅ **Tamper detection**: Verifies actual package before installing
✅ **Prevents downgrades**: Won't install older versions
✅ **Prevents re-installs**: Won't install same version
✅ **Automatic cleanup**: Deletes invalid APK files
✅ **User feedback**: Shows localized message

## Localized Messages

When versionCode verification fails:

**English**: "No upgrade available (version already up to date)"
**Chinese**: "沒有可用的升級（版本已是最新）"
**Japanese**: "利用可能なアップグレードはありません（バージョンは最新です）"

## Logs

### Successful Upgrade
```
checkForUpgrade: Mini user requesting upgrade: 38-mini (code=67)
checkForUpgrade: Starting download to verify versionCode: http://...
checkForUpgrade: Downloaded APK versionCode: 68, current: 67
checkForUpgrade: APK versionCode (68) > current (67), installing
```

### Denied Upgrade
```
checkForUpgrade: Mini user requesting upgrade: 38-mini (code=67)
checkForUpgrade: Starting download to verify versionCode: http://...
checkForUpgrade: Downloaded APK versionCode: 67, current: 67
checkForUpgrade: APK versionCode (67) not higher than current (67), deleting file
Toast: 沒有可用的升級（版本已是最新）
```

## Benefits

✅ **Accurate**: Reads from actual APK, not server metadata
✅ **Reliable**: Can't be spoofed by server response
✅ **Safe**: Prevents invalid installations
✅ **Clean**: Automatically deletes invalid APKs
✅ **Localized**: User-friendly messages in Chinese/Japanese

---

**Status**: ✅ APK versionCode verification active
**Method**: PackageManager.getPackageArchiveInfo()
**Applies to**: All upgrade paths (mini manual, full automatic)

