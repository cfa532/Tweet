# APK Version Check Commands

## Your System Setup

**aapt location**: `/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt`

## Quick Commands

### Check Mini Version
```bash
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/mini/release/app-mini-release.apk | grep version
```

### Check Full Version
```bash
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep version
```

### Check Both
```bash
echo "=== Mini Version ===" && \
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/mini/release/app-mini-release.apk | grep "versionCode\|versionName" && \
echo -e "\n=== Full Version ===" && \
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  app/build/outputs/apk/full/release/app-full-release.apk | grep "versionCode\|versionName"
```

## Add aapt to PATH (Optional)

Add to your `~/.zshrc`:
```bash
export PATH=$PATH:/Users/cfa532/Library/Android/sdk/build-tools/35.0.0
```

Then reload:
```bash
source ~/.zshrc
```

After this, you can use just:
```bash
aapt dump badging your-app.apk | grep version
```

## Alternative: Use Latest Build Tools

```bash
# Use latest build-tools (36.1.0)
/Users/cfa532/Library/Android/sdk/build-tools/36.1.0/aapt dump badging your-app.apk | grep version
```

---

**Quick Tip**: The app now reads versionCode directly from downloaded APK files before installing!

