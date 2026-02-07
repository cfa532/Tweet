#!/bin/bash
# Quick script to check versionCode of both APKs

AAPT="/Users/cfa532/Library/Android/sdk/build-tools/35.0.0/aapt"
PROJECT_DIR="/Users/cfa532/Documents/GitHub/Tweet"

echo "=== Mini Version ==="
$AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/mini/release/app-mini-release.apk" | grep "package:" | head -1

echo ""
echo "=== Full Version ==="
$AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/full/release/app-full-release.apk" | grep "package:" | head -1

echo ""
echo "=== Summary ==="
MINI_CODE=$($AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/mini/release/app-mini-release.apk" | grep -oE "versionCode='[0-9]+'" | cut -d"'" -f2)
FULL_CODE=$($AAPT dump badging "$PROJECT_DIR/app/build/outputs/apk/full/release/app-full-release.apk" | grep -oE "versionCode='[0-9]+'" | cut -d"'" -f2)

echo "Mini versionCode: $MINI_CODE"
echo "Full versionCode: $FULL_CODE"

if [ "$FULL_CODE" -gt "$MINI_CODE" ]; then
    echo "✅ Full ($FULL_CODE) can replace Mini ($MINI_CODE)"
else
    echo "❌ Full ($FULL_CODE) cannot replace Mini ($MINI_CODE)"
fi

