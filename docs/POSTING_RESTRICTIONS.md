# Posting Restrictions System

## Overview

The app implements posting restrictions based on user activity to encourage upgrades and node setup:

1. **Mini Version**: Users with > 5 tweets must upgrade to full version to post new tweets
2. **Full Version**: Users with > 10 tweets and no node must set up a private node to post new tweets

**Important**: These restrictions **only block posting new tweets**. All other app functions remain available:
- ✅ Viewing tweets
- ✅ Liking/bookmarking tweets  
- ✅ Following users
- ✅ Browsing feed
- ✅ Commenting on existing tweets
- ❌ **Cannot post NEW tweets until requirement is met**

## Mini Version Posting Restriction

### Trigger Condition
```kotlin
if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
    // Block posting, show upgrade dialog
}
```

### Behavior
- User can use app normally until they try to post
- When user tries to compose a new tweet with > 5 existing tweets:
  - Dialog appears explaining upgrade requirement
  - Shows current download status
  - Provides "Upgrade Now" button (enabled when download complete)
  - Provides "Cancel" button to dismiss and keep editing

### Dialog Content

**Chinese:**
- Title: "需要升級"
- Message: "您已發布超過5條推文，需要升級到完整版才能繼續發布。完整版可以離線處理視頻並提供更多功能。"
- Buttons: "立即升級" or "下載中..." / "取消"

**Japanese:**
- Title: "アップグレードが必要です"
- Message: "新しいツイートを投稿するには、完全版へのアップグレードが必要です（5つ以上のツイートがあります）。完全版では、オフラインで動画を処理し、より多くの機能を利用できます。"
- Buttons: "今すぐアップグレード" or "ダウンロード中..." / "キャンセル"

### User Flow

1. **User opens app** (mini version, 6 tweets)
2. **Browses feed** ✅ Works normally
3. **Likes tweets** ✅ Works normally
4. **Clicks compose button** ✅ Opens compose screen
5. **Types tweet content** ✅ Works normally
6. **Clicks send button** ❌ Blocked
7. **Dialog appears**: "Upgrade required to post (you have > 5 tweets)"
8. **User options:**
   - Click "Upgrade Now" → Installs full version, can post after restart
   - Click "Cancel" → Returns to compose screen, content preserved, can keep editing
9. **App continues working** - all other features available

## Full Version Posting Restriction

### Trigger Condition
```kotlin
if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
    // Block posting, show node setup dialog
}
```

### Behavior
- User can use app normally until they try to post
- When user tries to compose a new tweet with > 10 existing tweets and no node:
  - Dialog appears explaining node requirement
  - Explains need for private infrastructure
  - Provides "OK" button to dismiss

### Dialog Content

**Chinese:**
- Title: "需要建立自己的節點"
- Message: "您已發布超過10條推文，需要建立自己的節點才能繼續發布。請建立您自己的節點以獲得更好的性能和存儲。"
- Button: "我知道了"

**Japanese:**
- Title: "独自ノードの設定が必要です"
- Message: "新しいツイートを投稿するには、独自のノードを設定する必要があります（10を超えるツイートがあります）。より良いパフォーマンスとストレージのために、独自のノードを確立してください。"
- Button: "わかりました"

### User Flow

1. **User opens app** (full version, 11 tweets, cloudDrivePort = 0)
2. **Browses feed** ✅ Works normally
3. **Likes tweets** ✅ Works normally
4. **Clicks compose button** ✅ Opens compose screen
5. **Types tweet content** ✅ Works normally
6. **Clicks send button** ❌ Blocked
7. **Dialog appears**: "Node setup required to post (you have > 10 tweets)"
8. **User clicks "OK"** → Returns to compose screen, content preserved
9. **User must set up node** (cloudDrivePort) to continue posting
10. **App continues working** - all other features available

## Technical Implementation

### Location
`ComposeTweetScreen.kt` - In the `onSendClick` handler

### Logic Flow
```kotlin
val onSendClick = {
    if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
        // Check mini version restriction
        if (BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 5) {
            showUpgradeRequiredDialog = true
        }
        // Check full version restriction
        else if (!BuildConfig.IS_MINI_VERSION && !appUser.isGuest() && appUser.tweetCount > 10 && appUser.cloudDrivePort == 0) {
            showNodeRequiredDialog = true
        }
        // Allowed to post
        else {
            // Upload tweet
            tweetFeedViewModel.uploadTweet(...)
            navController.popBackStack()
        }
    }
}
```

### Components

#### Upgrade Required Dialog (Mini Version)
- **Icon**: Upgrade icon (blue)
- **Title**: Localized upgrade requirement
- **Message**: Explains why upgrade is needed
- **Confirm Button**: "Upgrade Now" / "Downloading..."
  - Enabled only when download is complete
  - Launches Android package installer
- **Dismiss Button**: "Cancel"
  - Returns to compose screen
  - Content is preserved

#### Node Required Dialog (Full Version)
- **Icon**: Warning icon (red)
- **Title**: Localized node requirement
- **Message**: Explains why node is needed
- **Confirm Button**: "OK" / "Got it"
  - Returns to compose screen
  - Content is preserved

### Content Preservation
When user dismisses dialogs:
- Tweet content is **preserved**
- Attachments are **preserved**
- User can continue editing
- User can try sending again (dialog will appear again if still not resolved)

## Guest Users

**Guest users are NOT affected** by these restrictions:
- Mini version guests can post unlimited tweets (backend processing)
- Full version guests can post unlimited tweets
- Restrictions only apply to registered users (non-guest)

This encourages guest users to register while not blocking them from trying the app.

## Advantages of This Approach

### User Experience
✅ **Non-intrusive** - Doesn't block app usage, only posting
✅ **Clear messaging** - User knows exactly why and what to do
✅ **Gradual enforcement** - Users get value before restrictions kick in
✅ **Content preserved** - No lost work when dialog appears
✅ **Flexible** - User can dismiss and continue editing

### Business Logic
✅ **Encourages upgrade** - Natural progression for active users
✅ **Infrastructure awareness** - Ensures heavy users have dedicated resources
✅ **Resource management** - Backend not overloaded with heavy user content
✅ **Fair usage** - Light users not affected, heavy users contribute infrastructure

### Technical Benefits
✅ **Simple check** - Single condition at posting time
✅ **No state management** - No need to track app-wide blocking
✅ **Easy to test** - Trigger condition clear and testable
✅ **Maintainable** - Logic in one place (ComposeTweetScreen)

## Testing

### Test Mini Version Restriction

1. Build and install mini version:
```bash
./gradlew assembleMiniDebug
adb install app/build/outputs/apk/mini/debug/app-mini-debug.apk
```

2. Log in as non-guest user

3. Create 6 tweets (or modify appUser.tweetCount in code for testing)

4. Try to compose new tweet and click send

5. **Expected**: Upgrade dialog appears, posting is blocked

6. Click "Cancel" → Content preserved

7. Browse app → All features work normally

8. Try posting again → Dialog appears again

### Test Full Version Restriction

1. Build and install full version:
```bash
./gradlew assembleFullDebug
adb install app/build/outputs/apk/full/debug/app-full-debug.apk
```

2. Log in as non-guest user with cloudDrivePort = 0

3. Create 11 tweets (or modify appUser.tweetCount in code)

4. Try to compose new tweet and click send

5. **Expected**: Node setup dialog appears, posting is blocked

6. Click "OK" → Content preserved

7. Set up node (cloudDrivePort > 0)

8. Try posting again → Should succeed

### Verification Logs

```bash
# Monitor posting restrictions
adb logcat | grep -E "ComposeTweetScreen|appUser|tweetCount"
```

## Configuration

### Thresholds
Modify in `ComposeTweetScreen.kt`:

**Mini version tweet limit:**
```kotlin
if (appUser.tweetCount > 5)  // Change 5 to different threshold
```

**Full version tweet limit:**
```kotlin
if (appUser.tweetCount > 10)  // Change 10 to different threshold
```

### cloudDrivePort Check
Currently checks for `== 0`. If `null` should also trigger:
```kotlin
if ((appUser.cloudDrivePort == 0 || appUser.cloudDrivePort == null))
```

Note: In Kotlin, `cloudDrivePort` is `Int` (non-nullable by default), so `0` represents "not set".

## Future Enhancements

### Possible Improvements

1. **Progressive messaging**
   - Show soft warning at tweet count 3-4
   - Show hard block at tweet count > 5

2. **Alternative actions**
   - "Remind me later" option
   - "Delete old tweets" option

3. **Temporary bypass**
   - Allow 1-2 more tweets with warning
   - Grace period for node setup

4. **Analytics**
   - Track how many users hit the limit
   - Track conversion rate from warning to upgrade

5. **Help links**
   - Link to node setup guide
   - Link to upgrade benefits page

## Related Documentation

- 📖 **Upgrade System**: `docs/UPGRADE_SYSTEM.md`
- 🏗️ **Build Configuration**: `docs/BUILD_FLAVORS.md`
- 📚 **Quick Reference**: `UPGRADE_QUICK_REFERENCE.md`

---

**Last Updated**: October 14, 2025
**Implementation**: `ComposeTweetScreen.kt` (lines 143-175, 267-355)

