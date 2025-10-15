# Version Code Validation - Preventing Invalid Upgrades

## ✅ What Was Added

Added **versionCode validation** to prevent downgrades and ensure only valid upgrades proceed.

## Validation Logic

### For Mini Users (Manual Upgrade)

```kotlin
if (isMiniVersion && immediate) {
    val serverVersionCode = versionInfo["versionCode"]?.toIntOrNull()
    
    // DENY if server versionCode is not higher
    if (serverVersionCode != null && serverVersionCode > currentVersionCode) {
        // ✅ Proceed with download
        downloadAndInstall(context, downloadUrl)
    } else {
        // ❌ Deny upgrade
        Toast.makeText(context, 
            "No upgrade available (version already up to date)", 
            Toast.LENGTH_LONG).show()
    }
}
```

### For Full Users (Automatic Check)

```kotlin
// Check both version name AND version code
val shouldUpgrade = currentVersion < serverVersion || 
                   (serverVersionCode != null && serverVersionCode > currentVersionCode)

if (shouldUpgrade) {
    // Safety check: versionCode must be higher
    if (serverVersionCode != null && serverVersionCode <= currentVersionCode) {
        // ❌ Deny upgrade
        return
    }
    // ✅ Proceed with dialog
    showUpdateDialog(context, downloadUrl)
}
```

## Server API Requirements

The server's `checkUpgrade()` response must include **versionCode**:

```json
{
    "version": "39",           // Version name
    "versionCode": "69",       // Version code (must be higher!)
    "packageId": "abc123..."   // Package MimeiId
}
```

## Upgrade Scenarios

| Current | Server versionCode | Server version | Result |
|---------|-------------------|----------------|--------|
| Mini v67 | 68 | 38 | ✅ Upgrade (Mini → Full) |
| Mini v67 | 67 | 39 | ❌ Denied (same code) |
| Mini v67 | 66 | 39 | ❌ Denied (lower code) |
| Full v68 | 69 | 39 | ✅ Upgrade (Full v38 → v39) |
| Full v68 | 68 | 39 | ❌ Denied (same code) |
| Full v68 | 67 | 39 | ❌ Denied (lower code) |

## Version Planning

### Current Versions
- **Mini**: versionCode = 67, versionName = "38-mini"
- **Full**: versionCode = 68, versionName = "38"

### Next Release (v39)
When releasing version 39:

**Option A: Increment by 2**
- Mini v39: versionCode = 69
- Full v39: versionCode = 70

**Option B: Increment by 1**
- Full v39: versionCode = 69
- (No new mini version)

**Recommended**: Full version should always have a higher versionCode than the mini version of the same release.

## Benefits

✅ **Prevents downgrades**: Can't install older version over newer
✅ **Prevents lateral moves**: Can't install same version again
✅ **Android compatible**: Follows Android's upgrade requirements
✅ **Safety check**: Double validation (version name + code)
✅ **User feedback**: Shows message if upgrade not available

## User Messages

### Upgrade Denied (Same or Lower Version)
**English**: "No upgrade available (version already up to date)"

Could be localized:
- 🇨🇳 **Chinese**: "沒有可用的升級（版本已是最新）"
- 🇯🇵 **Japanese**: "利用可能なアップグレードはありません（バージョンは最新です）"

### Logs
```
checkForUpgrade: Server versionCode (67) not higher than current (67), skipping upgrade
```

## Testing

### Test Upgrade Validation

**Mini v67 → Server v68**:
```bash
# Expected: ✅ Download proceeds
# Logs: "server versionCode=68 > current=67"
```

**Mini v67 → Server v67**:
```bash
# Expected: ❌ Toast message appears
# Logs: "Server versionCode (67) not higher than current (67), skipping"
```

**Full v68 → Server v69**:
```bash
# Expected: ✅ Update dialog appears
# Logs: "Update available: server versionCode=69 > current=68"
```

## Implementation Summary

### Checks Added
1. **Current versionCode**: Read from PackageManager
2. **Server versionCode**: Read from checkUpgrade() response
3. **Comparison**: serverVersionCode > currentVersionCode
4. **Action**: Download only if higher, otherwise deny with message

### Applied To
- ✅ Mini version manual upgrade (compose button)
- ✅ Full version automatic upgrade (15s check)
- ✅ All upgrade paths validated

---

**Status**: ✅ Version code validation active
**Protection**: Prevents invalid upgrades
**APKs**: Both mini and full rebuilt with validation

