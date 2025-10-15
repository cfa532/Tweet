#!/bin/bash
# Quick debug script to check versions

echo "=== Checking Installed App ==="
adb shell dumpsys package us.fireshare.tweet | grep -E "versionCode|versionName" | head -5

echo ""
echo "=== Checking Built Mini APK ==="
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  /Users/cfa532/Documents/GitHub/Tweet/app/build/outputs/apk/mini/release/app-mini-release.apk \
  2>/dev/null | grep "versionCode" || echo "Mini APK not found"

echo ""
echo "=== Checking Built Full APK ==="
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  /Users/cfa532/Documents/GitHub/Tweet/app/build/outputs/apk/full/release/app-full-release.apk \
  2>/dev/null | grep "versionCode" || echo "Full APK not found in build/outputs"

echo ""
echo "=== Checking Full APK in app/full/release ==="
/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt dump badging \
  /Users/cfa532/Documents/GitHub/Tweet/app/full/release/app-full-release.apk \
  2>/dev/null | grep "versionCode" || echo "Full APK not found in app/full"

echo ""
echo "=== Instructions ==="
echo "Run: adb logcat | grep checkForUpgrade"
echo "Then try upgrade in app to see what happens"

