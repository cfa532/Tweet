#!/bin/bash
# Quick script to check versionCode of release APKs

AAPT="/Users/cfa532/Library/Android/sdk/build-tools/37.0.0/aapt"
PROJECT_DIR="/Users/cfa532/Documents/GitHub/Tweet"

echo "=== Full Version ==="
if [ -f "$PROJECT_DIR/app/build/outputs/apk/full/release/app-full-release.apk" ]; then
    $AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/full/release/app-full-release.apk" | grep "package:" | head -1
else
    echo "Full release APK not found"
fi

echo ""
echo "=== Play Version ==="
if [ -f "$PROJECT_DIR/app/build/outputs/apk/play/release/app-play-release.apk" ]; then
    $AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/play/release/app-play-release.apk" | grep "package:" | head -1
else
    echo "Play release APK not found"
fi
