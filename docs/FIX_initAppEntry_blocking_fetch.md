# Fix: Main Feed and AppUser Not Loading After Cache Clear

## Problem Description

After clearing all caches, the app fails to:
1. Load tweets on the main feed
2. Fetch appUser properly (shows as guest)

This issue occurred in the current code but worked correctly in commit `41a77802a6282fe35127ad53e20efe8bc51c7780`.

## Root Cause

The problem was in `HproseInstance.initAppEntry()`. The current version was launching the user fetch in a **background coroutine** that didn't block, causing a race condition:

### Current (Broken) Flow:
```kotlin
private suspend fun initAppEntry() {
    val entryIP = findEntryIP()
    appUser.baseUrl = "http://$entryIP"  // Set to ENTRY server IP
    
    // Load cached user
    val cachedUser = TweetCacheManager.getCachedUser(userId)
    if (cachedUser != null) {
        appUser = cachedUser.copy(baseUrl = "http://$entryIP")
    }
    
    // 🔴 PROBLEM: Launch in background and return immediately!
    TweetApplication.applicationScope.launch {
        val refreshedUser = fetchUser(userId, baseUrl = "", forceRefresh = true)
        if (refreshedUser != null) {
            appUser = refreshedUser  // Set to USER'S actual server IP
        }
    }
    
    // Returns here before fetch completes!
}
```

### What Happened When Starting With No Cache:

1. `initAppEntry()` sets `appUser.baseUrl = "http://125.229.161.122:8080"` (entry server)
2. Launches background fetch to get user's actual server
3. **Returns immediately** before fetch completes
4. `TweetFeedViewModel.initialize()` calls `waitForAppUser()`
5. `waitForAppUser()` sees `baseUrl` is not blank (it's the entry IP) and continues
6. Tries to fetch tweets from `http://125.229.161.122:8080` (entry server)
7. **FAILS!** The entry server doesn't have the user's tweets
8. User's actual tweets are on `http://60.163.239.46:8002` (different server)
9. Main feed shows empty, appUser appears incomplete

### Working (commit 41a77802) Flow:

```kotlin
private suspend fun initAppEntry() {
    val entryIP = findEntryIP()
    appUser.baseUrl = "http://$entryIP"
    
    // ✅ CORRECT: Fetch synchronously and WAIT for completion
    val refreshedUser = fetchUser(userId, baseUrl = "", forceRefresh = true)
    if (refreshedUser != null && refreshedUser.baseUrl != null) {
        appUser = refreshedUser  // Set to USER'S actual server IP
    }
    
    User.updateUserInstance(appUser, true)
    // Only returns after user fetch completes (with timeout)
}
```

## The Fix

Changed `initAppEntry()` to **wait for the user fetch to complete** (with a 15-second timeout) before returning:

```kotlin
// THEN: Fetch fresh data from network
// CRITICAL: We must wait for this to complete (with timeout) to ensure appUser.baseUrl
// is set to the user's actual server before TweetFeedViewModel tries to load tweets.
// Otherwise, tweets will fail to load when starting with cleared caches.
Timber.tag("initAppEntry").d("Fetching user data from network...")

try {
    // Use withTimeoutOrNull with 15 second timeout to prevent indefinite blocking
    val refreshedUser: User? = withTimeoutOrNull(15_000) {
        // Pass empty string to force IP re-resolution (like iOS fetchUser with baseUrl: "")
        fetchUser(userId, baseUrl = "", forceRefresh = true)
    }
    
    if (refreshedUser != null && !refreshedUser.baseUrl.isNullOrBlank()) {
        // Use the refreshed user's baseUrl
        appUser = refreshedUser
        TweetCacheManager.saveUser(refreshedUser)
        User.updateUserInstance(appUser, true)
        
        // CRITICAL: Force StateFlow to emit updated appUser to trigger UI recomposition
        _appUserState.value = appUser
        
        Timber.tag("initAppEntry")
            .d("✅ User fetch successful - baseUrl: ${appUser.baseUrl}, avatar: ${appUser.avatar}")
    } else {
        // Network fetch failed or timed out - use cached data if available
        Timber.tag("initAppEntry")
            .w("User fetch failed/timed out, continuing with cached/entry data")
    }
} catch (e: Exception) {
    Timber.tag("initAppEntry").e(e, "Error fetching user: ${e.message}")
}
```

## Key Changes

1. **Removed background launch**: No longer using `TweetApplication.applicationScope.launch {}`
2. **Blocking with timeout**: Using `withTimeoutOrNull(15_000)` to wait up to 15 seconds
3. **Ensures proper baseUrl**: By the time `initAppEntry()` returns, `appUser.baseUrl` is set to:
   - User's actual server IP (if network fetch succeeds)
   - Entry IP with cached data (if network fails but cache exists)
   - Entry IP (if both network and cache unavailable)

## Why This Works

When starting with cleared caches:

1. `initAppEntry()` resolves entry IP
2. **Waits** for user fetch to complete
3. User fetch gets the user's actual server IP: `http://60.163.239.46:8002`
4. Sets `appUser.baseUrl = "http://60.163.239.46:8002"`
5. `initAppEntry()` returns
6. `TweetFeedViewModel` calls `waitForAppUser()`
7. Fetches tweets from **user's actual server** `http://60.163.239.46:8002`
8. ✅ **Success!** Tweets load correctly

## Testing

### Before Fix:
- ❌ Main feed empty after clearing caches
- ❌ AppUser shows as guest
- ❌ Tweets fail to load on first launch
- ❌ User avatar not visible

### After Fix:
- ✅ Main feed loads tweets
- ✅ AppUser loaded correctly
- ✅ Tweets load on first launch
- ✅ User avatar displays

## Files Modified

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (lines 484-531)

## Related Issues

This fix addresses the same fundamental issue as documented in:
- `docs/CRITICAL_FIX_fetchUser_baseUrl.md` - Explains why each user needs their own baseUrl
- Commit `f9127426` - "Fix profile navigation and main feed loading issues"

## Notes

- The 15-second timeout ensures we don't block indefinitely if the network is unavailable
- If the fetch times out, we fall back to cached data (if available) or entry IP
- This matches the iOS implementation behavior where `initAppEntry()` blocks until user data is ready

