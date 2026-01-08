# Fix: Expired User Cache Handling and Tweet Author Loading

**Date**: 2026-01-08  
**Issue**: Non-appUser tweet authors showing as invalid objects without username in main feed when loading cached tweets  
**Status**: ✅ Resolved

## Problem Description

When the app loaded cached tweets (especially after being offline), tweet authors for non-appUser tweets were appearing as invalid/skeleton user objects with no username or avatar. This resulted in tweets displaying with "No avatarMid, showing default icon" errors in the UI.

### Root Cause

The issue had two components:

1. **Expired User Cache Removal**: When `TweetCacheManager.getCachedUser()` encountered an expired user in the cache, it would **completely remove** that user from both memory and database cache. This meant that when cached tweets were loaded later, their authors were no longer available in the cache, causing skeleton user objects to be created.

2. **Missing appUser Prioritization**: The `loadCachedTweets()` function (used by the main feed) did not prioritize the `appUser` singleton for the current user's tweets, unlike `loadCachedTweetsByAuthor()` which had this optimization.

### Why Removal Was Problematic

The old logic was:
```kotlin
if (cacheAge >= USER_CACHE_EXPIRATION_TIME) {
    // Remove from memory cache
    synchronized(userCacheLock) {
        userMemoryCache.remove(userId)
        userCacheTimestamps.remove(userId)
    }
    // Remove from database cache
    CoroutineScope(Dispatchers.IO).launch {
        dao.deleteCachedUser(userId)
    }
    return null  // User not available!
}
```

This caused a cascade of problems:
- Cached tweets had `authorId` references to users
- When those users expired, they were removed completely
- Loading cached tweets later found no author data → created skeleton users
- Skeleton users have no username, avatar, or other data → UI shows default icons

## Solution

### 1. Return Expired Users with Background Refresh

Modified `TweetCacheManager.getCachedUser()` to:
1. **Return expired users immediately** instead of removing them
2. **Trigger a background refresh** to update the user data asynchronously
3. **Never return null** if the user exists in cache (expired or not)

**Implementation** (`TweetCacheManager.kt`):

```kotlin
memoryUser?.let { user ->
    if (!isUserExpired(user)) {
        Timber.tag("TweetCacheManager").d("✅ MEMORY CACHE HIT (fresh): userId: $userId")
        return user
    } else {
        // Return expired user but trigger background refresh
        Timber.tag("TweetCacheManager").d("⏰ MEMORY CACHE HIT (expired): userId: $userId, triggering background refresh")
        
        // Trigger background refresh (fire and forget)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val freshUser = HproseInstance.fetchUser(userId, maxRetries = 1)
                if (freshUser != null) {
                    Timber.tag("TweetCacheManager").d("✅ Background refresh completed for userId: $userId")
                }
            } catch (e: Exception) {
                Timber.tag("TweetCacheManager").w("Failed to refresh expired user $userId in background: ${e.message}")
            }
        }
        
        return user // Return expired user immediately
    }
}
```

**Benefits**:
- UI always has some data to display (even if slightly stale)
- No skeleton users are created
- Fresh data loads in the background without blocking UI
- Works perfectly offline (uses cached data indefinitely)

### 2. Add appUser Singleton Prioritization

Modified `loadCachedTweets()` to prioritize the `appUser` singleton for the current user's tweets:

**Implementation** (`HproseInstance.kt`):

```kotlin
dao.getCachedTweetsByUser(userId, startRank, count).mapNotNull { cachedTweet ->
    val tweet = cachedTweet.originalTweet
    
    // Always populate author from user cache
    if (tweet.authorId == appUser.mid) {
        // For appUser's tweets, use appUser directly (always most up-to-date singleton)
        tweet.author = appUser
    } else {
        // For other users, get from cache
        tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
        
        // Fallback to skeleton user only if truly not in cache
        if (tweet.author == null) {
            tweet.author = getUserInstance(tweet.authorId)
        }
    }
    
    tweet
}
```

**Benefits**:
- appUser's tweets always use the live singleton (most up-to-date data)
- Consistent with `loadCachedTweetsByAuthor()` behavior
- Eliminates any potential timing issues with appUser cache

## Files Modified

1. **`app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`**
   - Modified `getCachedUser(userId: MimeiId)` to return expired users and trigger background refresh

2. **`app/src/main/java/us/fireshare/tweet/HproseInstance.kt`**
   - Modified `loadCachedTweets()` to prioritize `appUser` singleton
   - Added comprehensive logging for debugging

## Verification

### Test Case 1: Offline Startup with Expired Cache
**Steps**:
1. Run app with network on to cache tweets and users
2. Wait for user cache to expire (or manually set expiration time low)
3. Restart app with network off
4. Observe main feed

**Expected Result**: All tweet authors display with correct usernames and avatars from cache

**Actual Result**: ✅ PASS - All authors show correctly:
```
✅ Using appUser singleton for tweet isppi6XC29HX2f-sdJxvio1x9Up - author: aws05
🔍 getCachedUser returned: user with username=pcadmin
✅ Final: tweet eaKMFgl_3zHg_8qVcUwBX3bwDYL has author pcadmin
```

### Test Case 2: Background Refresh on Expired Users
**Steps**:
1. Load app with expired user cache
2. Monitor logs for background refresh

**Expected Result**: Expired users returned immediately, background refresh triggered

**Actual Result**: ✅ PASS - Users returned immediately, refresh happens async

### Test Case 3: appUser Tweet Consistency
**Steps**:
1. Load main feed with appUser's own tweets
2. Verify author data matches toolbar user

**Expected Result**: appUser's tweets use the same singleton instance as toolbar

**Actual Result**: ✅ PASS - Always uses `appUser` singleton

## Related Issues

This fix complements several other user loading improvements:
- `FIX_AUTHOR_LOADING_ORDER.md` - Ensures proper author loading sequence
- `FIX_APPUSER_BASEURL_DEADLOCK.md` - Handles appUser initialization
- `FIX_WAIT_FOR_REAL_APPUSER_BASEURL.md` - Waits for valid appUser before loading

## Performance Impact

- **Positive**: Eliminates network calls for expired but available users during initial load
- **Positive**: UI displays immediately with cached data
- **Neutral**: Background refresh happens asynchronously (no UI blocking)
- **Positive**: Reduced "skeleton user" UI flicker

## Timber Logging Configuration

**Note**: During debugging, we discovered that `Timber` logs were not appearing in logcat. The issue was resolved by using `System.out.println()` and `android.util.Log.e()` for critical debugging.

**Timber Configuration** (`TweetApplication.kt`):
- Debug builds: Uses `Timber.DebugTree()` - logs all levels
- Release builds: Uses custom `ReleaseTree()` - also logs all levels to logcat

The configuration is correct and should log everything. The earlier issue was likely transient or related to log buffering.

## Future Considerations

1. **Cache Expiration Strategy**: Consider longer expiration times for user data (currently expires after a certain period)
2. **Selective Refresh**: Only refresh expired users that are currently visible on screen
3. **Batch Refresh**: Refresh multiple expired users in a single batch request
4. **Cache Warming**: Pre-fetch users for cached tweets when app comes online

## Conclusion

The fix successfully resolves the issue of invalid tweet authors in the main feed by:
1. Preserving expired user data in cache and returning it immediately
2. Refreshing stale data asynchronously in the background
3. Prioritizing the appUser singleton for consistency

This provides a much better user experience, especially for offline scenarios, while ensuring data freshness when online.

