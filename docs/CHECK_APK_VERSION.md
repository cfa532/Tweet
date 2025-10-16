# How to Check APK VersionCode

## Method 1: Using aapt (Recommended)

```bash
# Check versionCode and versionName
aapt dump badging your-app.apk | grep -E "versionCode|versionName"
```

### For Your APKs

**Mini Version**:
```bash
aapt dump badging app/build/outputs/apk/mini/release/app-mini-release.apk | grep version
```

**Full Version**:
```bash
aapt dump badging app/build/outputs/apk/full/release/app-full-release.apk | grep version
```

### Output Format
```
package: name='us.fireshare.tweet' versionCode='67' versionName='38-mini'
```

## Method 2: Using aapt2

```bash
# If using aapt2
aapt2 dump badging your-app.apk | grep version
```

## Method 3: Using apkanalyzer (Android Studio)

```bash
# Get versionCode
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer manifest version-code your-app.apk

# Get versionName
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer manifest version-name your-app.apk
```

## Method 4: One-liner for Both

```bash
# Check both mini and full versions
echo "Mini version:" && aapt dump badging app/build/outputs/apk/mini/release/app-mini-release.apk | grep "versionCode\|versionName" && \
echo "Full version:" && aapt dump badging app/build/outputs/apk/full/release/app-full-release.apk | grep "versionCode\|versionName"
```

## What You Should See

### Mini Version
```
versionCode='67' versionName='38-mini'
```

### Full Version
```
versionCode='68' versionName='38'
```

## Verify Upgrade Path

```bash
# Extract just the version codes
MINI_CODE=$(aapt dump badging app/build/outputs/apk/mini/release/app-mini-release.apk | grep -oP "versionCode='\K[0-9]+")
FULL_CODE=$(aapt dump badging app/build/outputs/apk/full/release/app-full-release.apk | grep -oP "versionCode='\K[0-9]+")

echo "Mini versionCode: $MINI_CODE"
echo "Full versionCode: $FULL_CODE"

if [ "$FULL_CODE" -gt "$MINI_CODE" ]; then
    echo "✅ Full can replace Mini"
else
    echo "❌ Full cannot replace Mini"
fi
```

## Quick Reference

```bash
# Show all package info
aapt dump badging your-app.apk

# Show only version info
aapt dump badging your-app.apk | grep version

# Show only versionCode
aapt dump badging your-app.apk | grep -oE "versionCode='[0-9]+'" | cut -d"'" -f2

# Show only versionName
aapt dump badging your-app.apk | grep -oE "versionName='[^']+'" | cut -d"'" -f2
```

## Install aapt (if not available)

```bash
# macOS (via Homebrew)
brew install aapt

# Or use from Android SDK
export PATH=$PATH:$ANDROID_HOME/build-tools/34.0.0
```

---

**Quick Command**: `aapt dump badging your-app.apk | grep version`

