# Fix: AppUser BaseUrl Deadlock on Login with Non-Existent User

## Problem

When the app starts with a saved userId that doesn't exist on the server (deleted account, wrong server, etc.), the app would deadlock:

1. `TweetFeedViewModel` waits for `appUser.baseUrl` to become available (10 second timeout)
2. `appUser.baseUrl` never becomes available because it was cleared when the user fetch failed
3. App appears frozen with a loading spinner for 10 seconds, then shows empty feed

### Symptoms

```
TweetFeedViewModel: Waiting for appUser.baseUrl to be available (timeout: 10000ms)
... (10 seconds pass)
TweetFeedViewModel: Timeout waiting for appUser.baseUrl after 10045ms
TweetFeedViewModel: AppUser baseUrl not initialized, using cached tweets only
```

### Log Analysis

```
16:13:18.663 initAppEntry: Set baseUrl to IP: http://125.229.161.122:8080
16:13:18.667 initAppEntry: ⚠️ No cached user found, set baseUrl to entry IP
16:13:19.653 unwrapV2Response: API returned error: User not found
16:13:19.654 initAppEntry: User initialized with cached data. heWgeGkeBX2gaENbIBS_Iy1mdTS, appUser.baseUrl: null  ← DEADLOCK!
16:13:19.791 TweetFeedViewModel: Waiting for appUser.baseUrl to be available
16:13:29.836 TweetFeedViewModel: Timeout waiting for appUser.baseUrl after 10045ms
```

## Root Cause

In `HproseInstance.kt`:

### Step 1: initAppEntry Sets Entry IP BaseUrl

**Line 527-533**: When there's no cached user, sets entry IP as fallback:

```kotlin
} else {
    // No cached user, set baseUrl to entry IP as fallback
    appUser.baseUrl = "http://$entryIP"  // ← Sets baseUrl
    User.updateUserInstance(appUser, true)
    Timber.tag("initAppEntry").d("⚠️ No cached user found, set baseUrl to entry IP: ${appUser.baseUrl}")
}
```

### Step 2: fetchUser Clears BaseUrl When User Not Found

**Line 3280** in `updateUserFromServerWithRetry`:

```kotlin
val userData = unwrapV2Response<Map<String, Any>>(rawResponse)
val success = if (userData != null) {
    processUserDataResponse(user, userData, skipRetryAndBlacklist)
} else {
    // unwrapV2Response returned null (user not found)
    Timber.tag("updateUserFromServer").w("❌ NULL RESPONSE: userId: ${user.mid}")
    user.baseUrl = null  // ← BUG: Clears the entry IP baseUrl!
    false
}
```

**Why line 3280 exists**: When a user is not found, the code intentionally clears `baseUrl` to force fresh IP resolution on retry. This makes sense for retries within `fetchUser`, but causes the deadlock when there are no more retries and control returns to `initAppEntry`.

### Step 3: initAppEntry Doesn't Restore BaseUrl

**Line 568-572** (before fix): When fetch fails, it just logs a warning:

```kotlin
} else {
    // Network fetch failed or timed out - use cached data if available
    Timber.tag("initAppEntry").w("User fetch failed/timed out, continuing with cached/entry data")
    // ← BUG: Doesn't restore the baseUrl that was cleared at line 3280!
}
```

### Step 4: TweetFeedViewModel Waits Forever

**Line 189-201** in `TweetFeedViewModel.kt`:

```kotlin
private suspend fun waitForAppUser(timeoutMillis: Long = 10000L) {
    while (appUser.baseUrl.isNullOrBlank() && ...) {  // ← Waits forever
        kotlinx.coroutines.delay(200)
    }
}
```

Since `appUser.baseUrl` is null and will never become non-null, it waits until timeout.

## The Fix

In `initAppEntry` **line 568-578**, restore the entry IP baseUrl if it was cleared:

### Before:

```kotlin
} else {
    // Network fetch failed or timed out - use cached data if available
    Timber.tag("initAppEntry")
        .w("User fetch failed/timed out, continuing with cached/entry data")
}
```

### After:

```kotlin
} else {
    // Network fetch failed or timed out - restore entry IP baseUrl if needed
    // fetchUser may have cleared baseUrl when user not found (line 3280 in updateUserFromServerWithRetry)
    if (appUser.baseUrl.isNullOrBlank()) {
        appUser.baseUrl = "http://$entryIP"
        Timber.tag("initAppEntry")
            .w("User fetch failed, restored entry IP baseUrl: ${appUser.baseUrl}")
    } else {
        Timber.tag("initAppEntry")
            .w("User fetch failed/timed out, continuing with existing baseUrl: ${appUser.baseUrl}")
    }
}
```

## Why This Works

1. **Entry IP is always available**: `findEntryIP()` successfully found a working server
2. **Preserves fallback connectivity**: Even if the saved userId doesn't exist, the app can still connect to the entry server to browse public content
3. **Unblocks TweetFeedViewModel**: `waitForAppUser()` immediately succeeds because `baseUrl` is non-null
4. **Maintains retry logic**: The fix doesn't interfere with line 3280's retry logic within `fetchUser` - it only restores the baseUrl AFTER all retries are exhausted

## Expected Behavior After Fix

When a saved userId doesn't exist:

1. ✅ App starts normally (no 10-second freeze)
2. ✅ `appUser.baseUrl` is set to entry IP
3. ✅ User can browse public content or login with a different account
4. ✅ Log messages clearly indicate user fetch failed but app continues with entry IP

## Testing

1. **Reproduce**: 
   - Save a userId in preferences that doesn't exist on server
   - Restart app
   - Before fix: 10-second freeze
   - After fix: Starts normally

2. **Verify logs**:
```
initAppEntry: User fetch failed, restored entry IP baseUrl: http://...
TweetFeedViewModel: appUser.baseUrl became available after XXms: http://...
```

## Related Code

- `HproseInstance.kt` line 487-603: `initAppEntry()` function
- `HproseInstance.kt` line 3226-3328: `updateUserFromServerWithRetry()` function
- `HproseInstance.kt` line 3280: Line that clears baseUrl on user not found
- `TweetFeedViewModel.kt` line 189-201: `waitForAppUser()` function
- `TweetFeedViewModel.kt` line 82-119: `initialize()` function

## Prevention

When modifying user fetch logic:
- **Never leave `appUser.baseUrl` as null** after initialization completes
- **Always have a fallback baseUrl** (entry IP) for connectivity
- **Test with non-existent users** to ensure app doesn't deadlock

