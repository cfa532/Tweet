#!/bin/bash

# Deep Link Testing Script
# This script helps test deep links on Android devices

echo "=== Deep Link Testing Script ==="
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected. Please connect a device and enable USB debugging."
    exit 1
fi

echo "✅ Device connected"
echo ""

# Get package name (full debug version)
PACKAGE_NAME="us.fireshare.tweet.debug"

echo "Testing deep links for package: $PACKAGE_NAME"
echo ""

# Test URLs
TEST_URLS=(
    "http://fireshare.uk/tweet/agvvgWJmmXtji5FLTt768Plu3He/uCQDqhZgCGw3zpXtNSWo9Ftyn7Q"
    "https://fireshare.uk/tweet/agvvgWJmmXtji5FLTt768Plu3He/uCQDqhZgCGw3zpXtNSWo9Ftyn7Q"
)

echo "Available test commands:"
echo ""
echo "1. Test deep link (app not running):"
echo "   adb shell am start -a android.intent.action.VIEW -d \"http://fireshare.uk/tweet/TWEET_ID/AUTHOR_ID\" $PACKAGE_NAME"
echo ""
echo "2. Test deep link (app already running):"
echo "   adb shell am start -a android.intent.action.VIEW -d \"http://fireshare.uk/tweet/TWEET_ID/AUTHOR_ID\" $PACKAGE_NAME"
echo ""
echo "3. View logs:"
echo "   adb logcat -s DeepLink:TweetActivity:D"
echo ""
echo "Example test command:"
echo "   adb shell am start -a android.intent.action.VIEW -d \"${TEST_URLS[0]}\" $PACKAGE_NAME"
echo ""

# Ask user if they want to run a test
read -p "Do you want to test a deep link now? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo "Enter tweet ID:"
    read TWEET_ID
    echo "Enter author ID:"
    read AUTHOR_ID
    
    if [ -z "$TWEET_ID" ] || [ -z "$AUTHOR_ID" ]; then
        echo "❌ Both tweet ID and author ID are required"
        exit 1
    fi
    
    TEST_URL="http://fireshare.uk/tweet/$TWEET_ID/$AUTHOR_ID"
    echo ""
    echo "Testing deep link: $TEST_URL"
    echo ""
    
    adb shell am start -a android.intent.action.VIEW -d "$TEST_URL" $PACKAGE_NAME
    
    echo ""
    echo "✅ Deep link sent. Check the app and logs."
    echo "To view logs, run: adb logcat -s DeepLink:TweetActivity:D"
fi

