# Fix: Preserve baseUrl When User Fetch Fails Due to IPFS Instability

## Problem

After clearing cache and restarting the app, it failed to load `appUser` and main feed with the error:
- `fetchUser` returned "User not found" 
- `appUser.baseUrl` was set to `null`
- `TweetFeedViewModel` waited 10 seconds for `baseUrl`, timed out, and showed empty feed

## Root Cause

The IPFS network isn't perfectly stable - valid users can temporarily be unfound. The issue was in `updateUserFromServerWithRetry()`:

1. **Line 2888**: Saves `val originalBaseUrl = user.baseUrl` 
2. **Line 2935**: Clears `user.baseUrl = null` when response is null (to force retry with fresh IP)
3. **Line 2980**: Returns `false` **without restoring** the `originalBaseUrl`

This left `appUser.baseUrl` as `null` after all retries failed, causing the feed to fail loading.

## Understanding the Architecture

- **entryIP**: Entry point to the IPFS network (from `findEntryIP()`)
- **user's baseUrl**: The specific node where the user's data lives (resolved via `getProviderIP()`)
- These are **not necessarily the same**!

Setting `appUser.baseUrl` to the entryIP would be incorrect because:
- The entryIP is just a gateway to the network
- The user's data lives on a specific node resolved by `getProviderIP(userId)`
- Forcing the wrong baseUrl would cause all subsequent API calls to fail

## Solution

Preserve the user's last known working `baseUrl` when fetch fails due to IPFS instability:

### Changes in `updateUserFromServerWithRetry()`

1. **After exception handler** (when `skipRetryAndBlacklist` is true):
```kotlin
if (skipRetryAndBlacklist) {
    // Restore original baseUrl before returning
    if (!originalBaseUrl.isNullOrEmpty() && user.baseUrl.isNullOrEmpty()) {
        user.baseUrl = originalBaseUrl
        Timber.tag("updateUserFromServer").d("🔧 Restored originalBaseUrl after exception: $originalBaseUrl")
    }
    return false
}
```

2. **After final retry attempt**:
```kotlin
if (attempt < maxRetries) {
    val delayMs = attempt * 1000L
    delay(delayMs)
} else {
    // Last attempt failed - restore original baseUrl
    if (!originalBaseUrl.isNullOrEmpty() && user.baseUrl.isNullOrEmpty()) {
        user.baseUrl = originalBaseUrl
        Timber.tag("updateUserFromServer").d("🔧 Restored originalBaseUrl after final attempt exception: $originalBaseUrl")
    }
}
```

3. **After all retries exhausted**:
```kotlin
Timber.tag("updateUserFromServer").e("❌ ALL RETRIES FAILED: userId: ${user.mid}, maxRetries: $maxRetries")

// CRITICAL: Restore original baseUrl if all retries failed
// Don't leave baseUrl as null - IPFS network instability can cause temporary failures
// Preserve the last known working baseUrl so user can retry later
if (!originalBaseUrl.isNullOrEmpty() && user.baseUrl.isNullOrEmpty()) {
    user.baseUrl = originalBaseUrl
    Timber.tag("updateUserFromServer").d("🔧 Restored originalBaseUrl after all retries failed: $originalBaseUrl")
}
```

## Behavior After Fix

### Scenario 1: User with cached data + IPFS instability
1. App loads cached user data immediately (shows UI)
2. Background fetch fails due to IPFS instability
3. `originalBaseUrl` is restored (from previous successful fetch)
4. User can continue using the app with cached data
5. Feed loads because `appUser.baseUrl` is preserved

### Scenario 2: No cached data + IPFS instability  
1. App attempts to fetch user from network
2. Fetch fails due to IPFS instability (with retries)
3. `originalBaseUrl` is restored (if it existed)
4. UI shows with available data
5. User can retry later when IPFS is stable

### Scenario 3: Normal operation
1. `fetchUser` succeeds on first or retry attempt
2. User data is updated with fresh info
3. `baseUrl` is set to the correct node IP from server response
4. Everything works normally

## Key Principles

1. **Never clear userId** - IPFS instability is temporary, user is still logged in
2. **Preserve baseUrl** - Last known working IP should be kept for retry
3. **Retry with backoff** - Already implemented in `fetchUser` (maxRetries = 2)
4. **Use cached data** - Show cached data immediately while fetching fresh data in background

## API Signature Verification

The `get_user` API call uses the correct signature (verified against backend code):

```kotlin
val entry = "get_user"
val params = mapOf(
    "aid" to appId,           // ✅ Backend: request.aid
    "ver" to "last",          // ✅ Backend: ver:"last"
    "version" to "v3",        // ✅ Backend: request.version
    "userid" to user.mid      // ✅ Backend: request["userid"]
)
```

Backend v3 behavior:
- If user found locally: returns user data
- If user not found locally: returns `{success: false, message: "User not found"}`
- Does NOT attempt to get provider IP (v2 behavior)

## Testing

Build verified successful:
```bash
./gradlew assembleFullDebug
# BUILD SUCCESSFUL in 9s
```

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` - Main fix
- Lines modified: 2965-2988 (exception handler and retry exhaustion)

