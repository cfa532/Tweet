# Android Studio Build & Install Guide

## Build and Install Mini Version

### Step 1: Open Project in Android Studio

If not already open:
```bash
open -a "Android Studio" /Users/cfa532/Documents/GitHub/Tweet
```

Wait for Gradle sync to complete.

### Step 2: Select Mini Build Variant

1. Click **View** → **Tool Windows** → **Build Variants**
   - Or press `⌘7` (Command+7) on Mac
   
2. In the **Build Variants** panel, find the `:app` module

3. Click the dropdown under **Active Build Variant**

4. Select: **`miniRelease`** (or `miniDebug` for debugging)

**Available variants**:
- `miniDebug` - Mini version with debugging enabled
- `miniRelease` - Mini version, production build
- `fullDebug` - Full version with debugging
- `fullRelease` - Full version, production build

### Step 3: Connect Your Android Device

1. Enable **Developer Options** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   
2. Enable **USB Debugging**:
   - Settings → Developer Options → USB Debugging → ON
   
3. Connect device via USB cable

4. Allow USB debugging when prompted on device

5. Verify connection in Android Studio:
   - You should see your device in the device dropdown (top toolbar)

### Step 4: Run/Install

**Option A: Run Button (Installs and Launches)**
1. Click the green **Run** button (▶️) in the toolbar
2. Or press `Ctrl+R` (Control+R)
3. App will install and launch automatically

**Option B: Build Only (Just Install)**
1. Click **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. Wait for build to complete
3. Click **locate** in the notification
4. Or find APK at: `app/build/outputs/apk/mini/release/app-mini-release.apk`
5. Drag APK to device or install via: `adb install app/build/outputs/apk/mini/release/app-mini-release.apk`
    
## Build and Install (Full) via Command Line

These steps are for developers who prefer using the terminal.

### Step 1: Build the APK (release or debug)

1.  Open your terminal.

2.  Navigate to the project's root directory:
    ```bash
    cd /Users/cfa532/Documents/GitHub/Tweet
    ```

3.  Run **one** of the following, depending on what you want to install:
    ```bash
    # Full RELEASE APK (used for production testing)
    ./gradlew assembleFullRelease
    ```
    Alternatively, you can build all release variants (full, mini, play):
    ```bash
    # All release variants (full, mini, play)
    ./gradlew assembleRelease

    # Full DEBUG APK (what you usually install while developing)
    ./gradlew assembleFullDebug
    ```

### Step 2: Locate the APK

**Full variants:**

- Full **release** APK  
  `app/build/outputs/apk/full/release/app-full-release.apk`

- Full **debug** APK  
  `app/build/outputs/apk/full/debug/app-full-debug.apk`

**Mini variants (for reference):**

- Mini **debug** APK  
  `app/build/outputs/apk/mini/debug/app-mini-debug.apk`

- Mini **release** APK  
  `app/build/outputs/apk/mini/release/app-mini-release.apk`

### Step 3: Install the APK on a specific device

1.  Make sure your device is connected and recognized by ADB:

    ```bash
    adb devices
    ```

    Example output:

    ```text
    List of devices attached
    FEC5T19A22022812    device
    ```

2.  Use `adb install` to install the APK on your specific device.  
    Replace `DEVICE_SERIAL` with your device's serial number from `adb devices` (for example: `FEC5T19A22022812`).

    ```bash
    # Full RELEASE APK
    adb -s DEVICE_SERIAL install app/build/outputs/apk/full/release/app-full-release.apk

    # Full DEBUG APK (most common during development)
    adb -s DEVICE_SERIAL install app/build/outputs/apk/full/debug/app-full-debug.apk
    ```

    For example, for a device with serial `FEC5T19A22022812` and a full **debug** APK:

    ```bash
    adb -s FEC5T19A22022812 install -r app/build/outputs/apk/full/debug/app-full-debug.apk
    ```

    If you rely on Android Studio's bundled Android SDK (adb not on PATH), use the full path to its `adb` binary.  
    On macOS, this is typically:

    ```bash
    /Users/<username>/Library/Android/sdk/platform-tools/adb -s DEVICE_SERIAL install -r app/build/outputs/apk/full/debug/app-full-debug.apk
    ```

    Replace:
    - `<username>` with your macOS account name (for you: `cfa532`)
    - `DEVICE_SERIAL` with the actual serial from `adb devices` (for example: `FEC5T19A22022812`)
    If you only have one device connected, you can omit the `-s DEVICE_SERIAL` part.

    **Quick copy/paste command for this project (full debug APK):**

    ```bash
    /Users/cfa532/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
    ```

    Use this whenever someone needs the exact install command—no extra explanation required.

## Quick Reference

### Switch Between Mini and Full

**In Build Variants panel** (⌘7):
- Select `miniDebug` or `miniRelease` for mini version
- Select `fullDebug` or `fullRelease` for full version

### Device Selection

**Top toolbar**: Device dropdown shows connected devices
- Physical devices: Your phone/tablet
- Emulators: Virtual devices

### Build & Run Shortcuts

| Action | Mac Shortcut | Menu |
|--------|--------------|------|
| Run | `Ctrl+R` | Run → Run 'app' |
| Debug | `Ctrl+D` | Run → Debug 'app' |
| Build APK | `⌘⇧F9` | Build → Build APK(s) |
| Clean | N/A | Build → Clean Project |

## Troubleshooting

### "No devices found"
- Check USB connection
- Enable USB debugging on device
- Try different USB cable/port

### "Installation failed"
- Uninstall existing app first
- Check device storage space
- Enable "Install from unknown sources" if needed

### Build variant not changing
- Click **File** → **Sync Project with Gradle Files**
- Wait for sync to complete
- Try selecting variant again

### APK not installing
- Check signing configuration
- Verify device compatibility (minSdk = 29)
- Check logcat for errors

## For Your Setup

**Project**: `/Users/cfa532/Documents/GitHub/Tweet`

**Steps**:
1. Open in Android Studio
2. Build Variants (⌘7) → Select **`miniRelease`**
3. Connect Android device via USB
4. Click Run (▶️) or press `Ctrl+R`
5. Mini version installs and launches on your device

---

**Quick Tip**: Use `miniDebug` during development, `miniRelease` for distribution

