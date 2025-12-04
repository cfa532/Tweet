# Expired User Background Refresh Implementation

## Overview

When a cached user object is expired, the app now returns it immediately (non-blocking) and refreshes it from the server in a background thread. The user instance is updated automatically when the background refresh completes.

## Behavior

### For Non-Expired Cached Users
- ✅ Return immediately from cache
- ✅ No server fetch needed

### For Expired Cached Users
- ✅ Return expired cached user immediately (non-blocking)
- ✅ Launch background coroutine to refresh from server
- ✅ Update user instance automatically when refresh completes
- ✅ Update cache with fresh data

### For Cache Miss or Force Refresh
- ✅ Fetch from server synchronously (existing behavior)

## Implementation Details

### Location
`app/src/main/java/us/fireshare/tweet/HproseInstance.kt` - `getUser()` function (lines 2404-2458)

### Key Changes

1. **Check for expired cached users** (line 2411):
   ```kotlin
   else if (cachedUser.username != null && cachedUser.hasExpired && cachedUser.baseUrl != null) {
       // Return expired user immediately and refresh in background
   }
   ```

2. **Launch background refresh** (line 2426):
   ```kotlin
   TweetApplication.applicationScope.launch {
       // Background refresh logic
   }
   ```

3. **Prevent duplicate background refreshes** (lines 2416-2423):
   - Uses `ongoingUserUpdates` set to track in-progress refreshes
   - Only launches background refresh if not already in progress

4. **Update user instance and cache** (lines 2435-2438):
   - Updates user singleton via `getUserInstance()`
   - Saves fresh data to cache
   - Views automatically see updates (User is singleton)

### Background Refresh Flow

```
getUser(userId)
  ├─ Check cache
  ├─ If expired:
  │   ├─ Return expired user immediately
  │   ├─ Launch background coroutine
  │   │   ├─ Get user instance (singleton)
  │   │   ├─ Update from server
  │   │   ├─ Save to cache
  │   │   └─ User singleton updated (views see changes)
  │   └─ Cleanup ongoing updates tracking
  └─ Continue with existing flow if cache miss
```

## Benefits

1. **Non-blocking**: UI doesn't wait for server fetch when user is expired
2. **Automatic updates**: User instance updates automatically when background refresh completes
3. **Deduplication**: Prevents multiple simultaneous background refreshes for same user
4. **Better UX**: Users see data immediately (even if stale) while fresh data loads

## Logging

The implementation includes comprehensive logging:
- `⏰ CACHE EXPIRED`: Expired user returned immediately
- `🔄 BACKGROUND REFRESH START`: Background refresh started
- `✅ BACKGROUND REFRESH SUCCESS`: Background refresh completed successfully
- `❌ BACKGROUND REFRESH FAILED`: Background refresh failed
- `🧹 BACKGROUND REFRESH COMPLETE`: Background refresh cleanup

## Thread Safety

- Uses `userUpdateMutex` to protect `ongoingUserUpdates` set
- Uses singleton user instances via `getUserInstance()` for thread-safe updates
- Background refresh runs in `TweetApplication.applicationScope` (IO dispatcher)

## Testing

To test expired user behavior:
1. Clear cache or wait for user to expire (30 minutes)
2. Call `getUser(userId)` - should return expired user immediately
3. Check logs - should see background refresh logs
4. Wait for refresh - user instance should update automatically

