# Upgrade Logic Fix

## Issue
Mini version users clicking "Upgrade Now" didn't see any response.

## Root Cause
The upgrade logic was comparing version numbers even for manual mini version upgrades. If mini version was "38-mini" and server had "38", no dialog appeared.

## Solution
**Mini users requesting upgrade = ALWAYS show upgrade dialog, regardless of version**

### New Logic

```kotlin
fun checkForUpgrade(context: Context, immediate: Boolean = false) {
    // 1. If mini user manually requests upgrade → ALWAYS upgrade
    if (isMiniVersion && immediate) {
        // Don't compare versions, just upgrade
        val versionInfo = HproseInstance.checkUpgrade()
        if (versionInfo != null) {
            showUpdateDialog(context, "$hostIp/mm/${versionInfo["packageId"]}")
        }
        return
    }
    
    // 2. For full version or automatic checks → Compare versions
    if (currentVersion < serverVersion) {
        showUpdateDialog(...)
    }
}
```

### Flow

**Mini User Clicks "Upgrade Now"**:
1. `checkForUpgrade(context, immediate = true)`
2. Detects: `isMiniVersion = true`, `immediate = true`
3. **Immediately**: Queries server for package
4. **Shows**: Upgrade dialog
5. **Downloads**: Full version (regardless of version number)

**Full User (Automatic Check)**:
1. `checkForUpgrade(context, immediate = false)`
2. Detects: `isMiniVersion = false`
3. Compares: current vs server version
4. **Shows**: Dialog only if server version > current

### Key Changes

**Before**:
```kotlin
if (currentVersion < serverVersion || (isMiniVersion && immediate)) {
    showUpdateDialog(...)
}
```
Problem: Still required version comparison first

**After**:
```kotlin
// Handle mini user manual upgrade FIRST (short-circuit)
if (isMiniVersion && immediate) {
    showUpdateDialog(...)
    return  // Exit early, no version comparison
}

// Then handle version comparison for others
if (currentVersion < serverVersion) {
    showUpdateDialog(...)
}
```

## Test Results

Install the updated mini version:
```bash
adb install -r app/build/outputs/apk/mini/release/app-mini-release.apk
```

Expected behavior:
1. Click compose button with > 5 tweets
2. Upgrade dialog appears
3. Click "立即升級"
4. **Server upgrade dialog appears immediately**
5. Download and install full version

---

**Status**: ✅ Fixed - Mini users can always upgrade
**Build**: Ready in `app/build/outputs/apk/mini/release/app-mini-release.apk`

