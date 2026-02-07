# Launcher Badge Implementation
**Last Updated:** October 14, 2025

## Overview

Complete launcher badge system that shows notification counts on the device's app icon when new chat messages are received. The badge displays 1-9 for specific counts and "n" for 10+ messages, following common messaging app patterns.

## Badge Display Logic

### Number Display Rules
- **0 messages**: No badge shown
- **1-9 messages**: Shows actual number (1, 2, 3, ..., 9)
- **10+ messages**: Shows "n" (indicating many messages)

### Examples
```
1 new message  → Badge shows "1"
5 new messages → Badge shows "5"
9 new messages → Badge shows "9"
10 new messages → Badge shows "n"
99 new messages → Badge shows "n"
```

## Components

### 1. LauncherBadgeManager.kt

**Purpose:** Manages launcher badge count using ShortcutBadger library

**Key Methods:**
```kotlin
fun updateBadgeCount(context: Context, count: Int) {
    if (count > 0) {
        // Format badge text: 1-9 show numbers, 10+ show "n"
        val badgeText = when {
            count <= 9 -> count.toString()
            else -> "n"
        }
        ShortcutBadger.applyCount(context, count)
    }
}

fun formatBadgeText(count: Int): String {
    return when {
        count <= 0 -> ""
        count <= 9 -> count.toString()
        else -> "n"
    }
}

fun clearBadge(context: Context)
fun isBadgeSupported(context: Context): Boolean
```

### 2. BadgeStateManager.kt

**Purpose:** Manages badge state and integrates with launcher

**Features:**
- Automatic launcher badge updates when count changes
- Enhanced error handling and logging
- Thread-safe state management

**Key Methods:**
```kotlin
fun initialize(context: Context)
fun updateBadgeCount(count: Int)
fun getFormattedBadgeText(): String
fun clearBadge()
```

### 3. BadgeTestUtility.kt

**Purpose:** Testing utility for debugging badge functionality

**Methods:**
- `testBadgeCount(context, count)` - Test specific badge count
- `testBadgeFormatting(context)` - Test various count scenarios
- `testCompleteBadgeFlow(context)` - Test increment progression
- `testBadgeSupport(context)` - Check device support
- `clearBadge(context)` - Clear badge for testing

## How It Works

### MessageCheckWorker Flow
1. **Every 15 minutes**: Worker checks for new messages
2. **Counts new messages**: Filters out already-read messages
3. **Updates badge**: Calls `BadgeStateManager.updateBadgeCount(count)`
4. **Formats display**: Shows number 1-9 or "n" for 10+

### Real-time Updates
1. **ChatListViewModel**: Detects new messages in real-time
2. **Updates badge**: Immediately shows the new count
3. **Formats correctly**: Always follows the 1-9/"n" rule

### Badge Clearing
1. **User opens Chat**: Badge automatically clears
2. **No new messages**: Badge shows 0 (no badge visible)
3. **Manual clear**: `BadgeStateManager.clearBadge()`

## Integration Points

### 1. Application Initialization
`TweetApplication.onCreate()` initializes BadgeStateManager

### 2. Badge Updates
- **MessageCheckWorker**: Updates badge when new messages found (every 15 minutes)
- **ChatListViewModel.previewMessages()**: Updates badge when new messages detected
- **ChatListScreen**: Clears badge when entering chat list

### 3. Badge Display
- **BottomNavigationBar**: Shows badge count in chat tab
- **Launcher Icon**: Shows badge count on device's app icon

## Testing

### Manual Testing
```kotlin
// Test specific counts
BadgeTestUtility.testBadgeCount(context, 1)   // Shows "1"
BadgeTestUtility.testBadgeCount(context, 9)   // Shows "9"
BadgeTestUtility.testBadgeCount(context, 10)  // Shows "n"
BadgeTestUtility.testBadgeCount(context, 25)  // Shows "n"

// Test formatting logic
BadgeTestUtility.testBadgeFormatting(context)

// Test complete flow
BadgeTestUtility.testCompleteBadgeFlow(context)
```

### Expected Log Output
```
BadgeTestUtility: Testing badge count: 1 (display: '1')
BadgeTestUtility: Testing badge count: 9 (display: '9')
BadgeTestUtility: Testing badge count: 10 (display: 'n')
Badge updated: 9 -> 10 (display: 'n')
```

### Logging
All badge operations are logged with these tags:
- `LauncherBadgeManager`
- `BadgeStateManager`
- `BadgeTestUtility`
- `MessageCheckWorker`

## Dependencies

```kotlin
implementation("me.leolin:ShortcutBadger:1.1.22")
```

## Device Support

The ShortcutBadger library supports various launchers:
- ✅ Samsung: TouchWiz, One UI
- ✅ Google: Pixel Launcher
- ✅ Third-party: Nova, Apex, ADW, Action Launcher
- ✅ Many more Android launchers

## User Experience

### What Users See
- **1-9 messages**: Clear indication of exact number
- **10+ messages**: "n" indicates many new messages (common UX pattern)
- **No badge**: When all messages are read

### Benefits
1. **Clear Communication**: Users know exactly how many messages (1-9)
2. **Clean Design**: "n" prevents cluttered display for large numbers
3. **Familiar Pattern**: Matches user expectations from other apps
4. **Space Efficient**: Prevents badge overflow on small icons

## Error Handling

- Graceful fallback when launcher badge is not supported
- Comprehensive error logging for debugging
- Retry logic for network-related failures
- Null safety checks throughout

## Performance

- Lightweight operations with minimal impact
- No app performance degradation
- Minimal battery usage
- Efficient database queries for message checking

## Future Enhancements

Potential improvements:
- Custom badge styling options
- Badge sound notifications
- Badge vibration patterns
- Badge color customization
- Configurable "n" threshold (currently 10)
- Priority badges for important messages

