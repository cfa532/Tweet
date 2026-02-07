# Android Debug Setup and Logging Guide

This guide explains how to set up an Android emulator in debug mode and monitor your Tweet app logs using logcat.

## Prerequisites

- Android SDK installed (typically at `/Users/[username]/Library/Android/sdk`)
- Android emulator AVD created
- Gradle build system configured

## 1. Setting Up Environment Variables

First, set up your Android SDK environment:

```bash
export ANDROID_HOME=/Users/[username]/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
```

## 2. Starting the Android Emulator

### Check Available Emulators
```bash
emulator -list-avds
```

### Start Emulator in Background
```bash
emulator -avd [AVD_NAME]
```

Example:
```bash
emulator -avd Pixel_9a
```

### Verify Emulator is Running
```bash
adb devices
```

You should see output like:
```
List of devices attached
emulator-5554	device
```

## 3. Building and Installing the App

### Build Debug APK
```bash
./gradlew assembleMiniDebug
```

### Install APK on Emulator
```bash
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk
```

## 4. Starting the App

### Launch the App
```bash
adb shell am start -n us.fireshare.tweet.debug/us.fireshare.tweet.TweetActivity
```

### Verify App is Running
```bash
adb shell dumpsys activity activities | grep -A 5 -B 5 "us.fireshare.tweet"
```

## 5. Monitoring App Logs with Logcat

### Basic Logcat Commands

#### View All Logs (Verbose)
```bash
adb logcat -v threadtime
```

#### Filter for Your App Only
```bash
adb logcat -s "us.fireshare.tweet:D" "*:S"
```

#### View Recent Logs
```bash
adb logcat -d | grep -i "tweet\|fireshare" | tail -20
```

### Logcat Output Formats

#### Thread Time Format (Default)
```bash
adb logcat -v threadtime
```
Output: `10-17 07:23:11.424 17571 17648 D TweetCacheManager: ✅ MEMORY CACHE HIT`

#### Brief Format
```bash
adb logcat -v brief
```
Output: `D/TweetCacheManager(17648): ✅ MEMORY CACHE HIT`

#### Long Format
```bash
adb logcat -v long
```
Output: Multi-line format with full metadata

### Log Priority Levels

| Priority | Level | Description |
|----------|-------|-------------|
| V | Verbose | All logs |
| D | Debug | Debug logs (most common) |
| I | Info | Informational logs |
| W | Warning | Warning logs |
| E | Error | Error logs only |
| F | Fatal | Fatal errors only |

### Filtering Examples

#### Show Only Errors and Warnings
```bash
adb logcat "*:W"
```

#### Show Specific Tag at Debug Level
```bash
adb logcat "TweetCacheManager:D" "*:S"
```

#### Show Multiple Tags
```bash
adb logcat "TweetItem:D TweetCacheManager:D" "*:S"
```

## 6. Understanding Your App's Logs

### Common Log Patterns in Tweet App

#### Successful Tweet Loading
```
D TweetItem: Fetching original tweet: G2gBzYoO_qK75ZQh5DSe1iD3QhX from author: yBlnmA15ho3EBISaHw7AYN0tvVP
D TweetCacheManager: ✅ MEMORY CACHE HIT: userId: yBlnmA15ho3EBISaHw7AYN0tvVP, username: mini
D TweetItem: Original tweet loaded successfully: G2gBzYoO_qK75ZQh5DSe1iD3QhX
```

#### Memory Management
```
I are.tweet.debug: NativeAlloc concurrent mark compact GC freed 4224KB AllocSpace bytes, 3(14MB) LOS objects, 33% free, 46MB/70MB
```

#### AppOps Warnings (Non-Critical)
```
E AppOps: attributionTag not declared in manifest of us.fireshare.tweet.debug
```

#### Resource Access Issues
```
E are.tweet.debug: Failed to query component interface for required system resources: 6
```

## 7. Advanced Logcat Features

### Save Logs to File
```bash
adb logcat -s "us.fireshare.tweet:D" "*:S" > app_logs.txt
```

### Clear Log Buffer
```bash
adb logcat -c
```

### View Specific Log Buffers
```bash
# View radio logs
adb logcat -b radio

# View system logs
adb logcat -b system

# View all buffers
adb logcat -b all
```

### Color-Coded Output
```bash
adb logcat -v color
```

## 8. Troubleshooting Common Issues

### Emulator Not Starting
- Check available disk space
- Ensure hardware acceleration is enabled
- Try different AVD configurations

### App Not Installing
- Check if app is already installed: `adb shell pm list packages | grep us.fireshare.tweet`
- Uninstall previous version: `adb uninstall us.fireshare.tweet.debug`
- Rebuild the APK

### No App Logs Appearing
- Verify app is actually running: `adb shell dumpsys activity activities`
- Check if app has logging enabled in code
- Try broader filters: `adb logcat "*:V"`

### App Crashes
- Check for crash logs: `adb logcat -b crash`
- Look for stack traces in main log buffer
- Use `adb bugreport` for detailed system state

## 9. Quick Reference Commands

```bash
# Complete setup sequence
export ANDROID_HOME=/Users/[username]/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
emulator -avd Pixel_9a &
sleep 15
adb devices
./gradlew assembleMiniDebug
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk
adb shell am start -n us.fireshare.tweet.debug/us.fireshare.tweet.TweetActivity
adb logcat -s "us.fireshare.tweet:D" "*:S"
```

## 10. Best Practices

1. **Always use debug builds** for development and logging
2. **Filter logs appropriately** to avoid information overload
3. **Save important logs** to files for later analysis
4. **Monitor memory usage** to detect leaks
5. **Use specific tags** in your logging code for better filtering
6. **Clear log buffer** before testing to get clean logs
7. **Test on different emulator configurations** for compatibility

## 11. Integration with Android Studio

If using Android Studio:
- Open **Logcat** window (View → Tool Windows → Logcat)
- Select your device and app package
- Use built-in filters for easier log monitoring
- Set up custom logcat filters for your specific tags

This guide provides a complete workflow for debugging your Tweet app on Android emulators with comprehensive log monitoring capabilities.
