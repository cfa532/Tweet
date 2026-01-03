# Fix: User Fetch Failure During initAppEntry Should Retry

## Problem

When `initAppEntry` attempted to fetch user data and received "User not found" error, it would:
1. **Fail silently** - Restore entry IP and proceed
2. **Log misleading message** - "🚀 Fresh user data loaded" when NO data was loaded
3. **Show UI anyway** - Tell `TweetActivity` that user is loaded when it actually failed
4. **No retry logic** - Give up after single failure

### Actual Log Output (Bug):
```
16:51:37.654  unwrapV2Response    W  API returned error: User not found
16:51:37.666  initAppEntry        W  User fetch failed, restored entry IP baseUrl
16:51:37.667  initAppEntry        D  🚀 Fresh user data loaded, showing UI now  ❌ LIE!
16:51:37.667  TweetActivity       D  AppUser loaded, showing UI  ❌ USER NOT LOADED!
```

## Root Cause

The code structure in `initAppEntry` (lines 557-608) had a logic flaw:

```kotlin
// Attempt to fetch user (with 15s timeout)
val refreshedUser = fetchUser(userId, baseUrl = "", forceRefresh = true)

if (refreshedUser != null) {
    // Success path
} else {
    // Failure path - restore entry IP
    // But then continues...
}

// ALWAYS executes if no cached user:
if (!hasCachedUser) {
    Timber.d("🚀 Fresh user data loaded")  // ❌ WRONG! Fetch failed!
    onBaseUrlReady?.invoke()  // Shows UI anyway
}
```

**The bug:** Code claimed "Fresh user data loaded" regardless of whether fetch succeeded or failed.

## Solution

Implemented **retry logic with exponential backoff**:

### Changes:
1. **3 retry attempts** with increasing delays (2s, 4s, 6s)
2. **20 second timeout** per attempt (increased from 15s)
3. **Accurate logging** - Only say "Fresh user data loaded" when data actually loaded
4. **Clear failure handling** - Explicit "User fetch failed after all retries" message

### New Flow:
```kotlin
var refreshedUser: User? = null
val maxFetchAttempts = 3

for (attempt in 1..maxFetchAttempts) {
    Timber.d("User fetch attempt $attempt/$maxFetchAttempts")
    
    refreshedUser = withTimeoutOrNull(20_000) {
        fetchUser(userId, baseUrl = "", forceRefresh = true)
    }
    
    if (refreshedUser != null) {
        Timber.d("✅ User fetch successful on attempt $attempt")
        break  // Success!
    }
    
    if (attempt < maxFetchAttempts) {
        val delayMs = 2000L * attempt  // Exponential backoff
        Timber.w("Failed, retrying after ${delayMs}ms...")
        delay(delayMs)
    }
}

// Only show "Fresh data loaded" if actually loaded
if (refreshedUser != null) {
    // Update appUser...
    if (!hasCachedUser) {
        Timber.d("🚀 Fresh user data loaded, showing UI now")  // ✅ TRUTH!
        onBaseUrlReady?.invoke()
    }
} else {
    // All retries failed
    if (!hasCachedUser) {
        Timber.w("⚠️ User fetch failed after all retries, showing UI with entry IP only")
        onBaseUrlReady?.invoke()
    }
}
```

## Expected Log Output (After Fix):

### Success After Retry:
```
initAppEntry  D  User fetch attempt 1/3 for userId: a8UM3x8Nx...
initAppEntry  W  Failed, retrying after 2000ms...
initAppEntry  D  User fetch attempt 2/3 for userId: a8UM3x8Nx...
initAppEntry  D  ✅ User fetch successful on attempt 2
initAppEntry  D  🚀 Fresh user data loaded, showing UI now  ✅ TRUTH!
```

### Failure After All Retries:
```
initAppEntry  D  User fetch attempt 1/3 for userId: a8UM3x8Nx...
initAppEntry  W  Failed, retrying after 2000ms...
initAppEntry  D  User fetch attempt 2/3 for userId: a8UM3x8Nx...
initAppEntry  W  Failed, retrying after 4000ms...
initAppEntry  D  User fetch attempt 3/3 for userId: a8UM3x8Nx...
initAppEntry  E  ❌ User fetch failed after 3 attempts
initAppEntry  W  ⚠️ User fetch failed after all retries, showing UI with entry IP only  ✅ HONEST!
```

## Benefits

1. **Resilience** - Transient network errors won't prevent app startup
2. **Honesty** - Logs accurately reflect what happened
3. **Better UX** - Users see correct state instead of broken UI with failed data fetch
4. **Debugging** - Clear log trail showing retry attempts and outcomes

## Why "User not found" Might Occur

Even with correct `appId` and `userId`, this error can happen due to:
1. **Network timing issues** - Request reaches server before data synced
2. **Server routing** - Wrong server node handling the request
3. **Database lag** - User data not yet replicated to queried node
4. **Temporary glitches** - Server internal errors masquerading as "not found"

**Retry logic handles all these cases gracefully.**

## Testing Recommendations

Test these scenarios:
1. **Normal startup** - Should succeed on attempt 1
2. **Slow network** - Should succeed within timeout
3. **Server error** - Should retry and eventually succeed or fail gracefully
4. **No network** - Should show degraded UI after retries exhausted

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (lines 557-646)

## Related Fixes

1. ✅ **FIX_APPID_EMPTY_TIMING_ISSUE.md** - Ensured appId is never empty
2. ✅ **FIX_APPUSER_BASEURL_DEADLOCK.md** - Fixed baseUrl being cleared
3. ✅ **FIX_HEALTH_CHECK_TIMEOUT_PERFORMANCE.md** - Reduced health check timeouts
4. ✅ **This fix** - Added retry logic for user fetch failures

Together, these fixes make app initialization much more robust!

