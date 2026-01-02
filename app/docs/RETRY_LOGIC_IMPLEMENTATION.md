# Retry Logic Implementation

## Overview
Added retry logic with exponential backoff to key network functions that were failing without retry attempts.

## Changes Made

### 1. `getTweetFeed()` - Line ~1483
**Before:** Failed on first network error and returned empty list
**After:** 
- Retries up to 2 times (3 total attempts) with exponential backoff (1s, 2s)
- Refreshes user's baseUrl on retry attempts by calling `fetchUser(user.mid, baseUrl = "")`
- Only retries on network-related errors (checked via `ErrorMessageUtils.isNetworkError()`)
- Logs retry attempts with clear indicators (🔄, ⏳, ✅, ❌)

**Key Features:**
```kotlin
suspend fun getTweetFeed(
    user: User = appUser,
    pageNumber: Int = 0,
    pageSize: Int = 20,
    entry: String = "get_tweet_feed",
    maxRetries: Int = 2  // NEW: default 2 retries
): List<Tweet?>
```

- On retry: Calls `fetchUser(user.mid, baseUrl = "")` to force refresh of baseUrl
- Updates `user.hproseService` and `user.baseUrl` with fresh values
- Uses exponential backoff: `minOf(5000L, 1000L * (1 shl attempt))` → 1s, 2s, 4s

### 2. `getFollowings()` - Line ~1435
**Before:** Failed on first network error and returned alpha IDs
**After:**
- Same retry pattern as `getTweetFeed()`
- Refreshes user's baseUrl on retry attempts
- Retries up to 2 times with exponential backoff

**Key Features:**
```kotlin
suspend fun getFollowings(user: User, maxRetries: Int = 2): List<MimeiId>
```

### 3. `getFans()` - Line ~1459
**Before:** Failed on first network error and returned null
**After:**
- Same retry pattern as other functions
- Refreshes user's baseUrl on retry attempts  
- Retries up to 2 times with exponential backoff

**Key Features:**
```kotlin
suspend fun getFans(user: User, maxRetries: Int = 2): List<MimeiId>?
```

## Retry Pattern

All three functions now follow this pattern:

```kotlin
var lastError: Exception? = null
for (attempt in 0..maxRetries) {
    try {
        val forceRefresh = attempt > 0
        if (forceRefresh) {
            // Refresh user's baseUrl
            val refreshedUser = fetchUser(user.mid, baseUrl = "")
            if (refreshedUser != null) {
                user.hproseService = refreshedUser.hproseService
                user.baseUrl = refreshedUser.baseUrl
            }
        }
        
        // Attempt the network call
        val response = user.hproseService?.runMApp<>(entry, params)
        
        // Process response and return on success
        return result
        
    } catch (e: Exception) {
        lastError = e
        val isNetworkError = ErrorMessageUtils.isNetworkError(e)
        
        if (isNetworkError && attempt < maxRetries) {
            val delayMs = minOf(5000L, 1000L * (1 shl attempt))
            delay(delayMs)
            continue
        }
        
        // Log error and fail if not retrying
    }
}

// All retries exhausted
return defaultValue
```

## Backward Compatibility

All functions maintain backward compatibility:
- `maxRetries` parameter has default value of 2
- Existing calls work without any changes
- No changes required to ViewModels or other calling code

## Expected Behavior

### Scenario 1: Connection Reset (from logs)
```
2026-01-02 16:28:05.972 getTweetFeed - First attempt
2026-01-02 16:28:06.256 Connection reset
                        ↓
2026-01-02 16:28:07.256 🔄 Retry attempt 1: Refreshing baseUrl
2026-01-02 16:28:07.256 ✅ Refreshed baseUrl: http://x.x.x.x:8080
                        ↓
2026-01-02 16:28:07.xxx Second attempt with new baseUrl
```

### Scenario 2: Connection Refused
If retry also fails:
```
2026-01-02 16:28:08.256 ⏳ Waiting 2s before retry (attempt 2/3)
                        ↓
2026-01-02 16:28:10.256 🔄 Retry attempt 2: Refreshing baseUrl
                        ↓
2026-01-02 16:28:10.xxx Third attempt with refreshed baseUrl
```

## Error Types Handled

The following network errors will trigger retry:
- `SocketException` (Connection reset, Connection refused)
- `ConnectException` (Failed to connect)
- `SocketTimeoutException` (Timeout)
- `UnknownHostException` (DNS failure)
- Other network-related IOExceptions

Non-network errors (e.g., parsing errors, authentication failures) will NOT trigger retry.

## Testing

To test the retry logic:
1. Start the app and monitor logs
2. Temporarily disconnect from network or simulate network failure
3. Look for retry log messages:
   - `🔄 Retry attempt X: Refreshing user's baseUrl`
   - `⏳ Network error detected, waiting Xs before retry`
   - `✅ Successfully fetched ... after retry`
   - `❌ All retry attempts exhausted`

## Related Files

- `HproseInstance.kt` - Core retry implementation
- `ErrorMessageUtils.kt` - Network error detection
- `TweetFeedViewModel.kt` - Calls `getTweetFeed()`
- `UserViewModel.kt` - Calls `getFans()` and `getFollowings()`

## Additional Fix: Prevent Null Overwriting Cached User

### Issue
When `getProviderIP` returns `null` (due to network failure), the code was treating it as "user not found" and clearing the user's `baseUrl` at line 3104, overwriting valid cached data with null.

### Root Cause
In `resolveAndUpdateBaseUrl()`, when `getProviderIP` returned null, the code just logged a warning but didn't throw an exception. The flow would continue, and later when `unwrapV2Response` also returned null, the code at line 3104 would set `user.baseUrl = null`, destroying the cached baseUrl.

### Fix Applied (Line ~3037)
**Before:**
```kotlin
} else {
    Timber.tag("updateUserFromServer").w("⚠️ getProviderIP returned null for userId: ${user.mid}")
}
```

**After:**
```kotlin
} else {
    // getProviderIP returned null - this is a network error, not "user not found"
    // Throw exception to trigger retry logic without clearing cached baseUrl
    Timber.tag("updateUserFromServer").w("⚠️ getProviderIP returned null for userId: ${user.mid}, attempt: $attempt/$maxRetries")
    throw Exception("Failed to resolve provider IP for user: ${user.mid}")
}
```

### How It Works
1. When `getProviderIP` fails (returns null), an exception is thrown
2. The exception is caught by the retry loop's catch block
3. The catch block restores `originalBaseUrl` (lines 3138-3154)
4. The retry logic attempts again with the preserved baseUrl
5. Cached user data is never overwritten with null

### Expected Behavior
```
Attempt 1: getProviderIP returns null
         → Exception thrown
         → originalBaseUrl restored
         → Retry scheduled

Attempt 2: getProviderIP called again with preserved baseUrl
         → Either succeeds or fails with proper error handling
         → baseUrl never set to null inappropriately
```

## Future Improvements

1. **Image Downloads**: Consider adding baseUrl refresh logic to image downloads
2. **Configurable Retries**: Make maxRetries configurable per call based on importance
3. **Circuit Breaker**: Implement circuit breaker pattern for persistent failures
4. **Metrics**: Track retry success/failure rates for monitoring

