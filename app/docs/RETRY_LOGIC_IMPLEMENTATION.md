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

## Additional Fix: Distinguish Network Errors from "User Not Found"

### Issue
When `getProviderIP` returns `null`, it could mean two very different things:
1. **Network error** occurred (should retry)
2. **User doesn't exist** or no IPs available (should NOT retry)

The original code couldn't distinguish these cases, treating both as network errors and retrying unnecessarily.

### Root Cause
The `_getProviderIP()` function was catching all exceptions and returning `null`, making it impossible to distinguish:
- Network failures (exception occurred) → Should retry
- User not found (server responded with empty IP list) → Should fail gracefully

### Fix Applied

#### 1. Modified `_getProviderIP()` (Line ~3533)
**Before:**
```kotlin
return try {
    val rawResponse = hproseService?.runMApp<Any>(entry, params)
    val ipArray = unwrapV2Response<List<String>>(rawResponse)
    // ...
    return tryIpAddresses(ipArray)
} catch (e: Exception) {
    Timber.tag("getProviderIP").e(actualException, "Error getting provider IPs")
    null  // ❌ Can't distinguish error type
}
```

**After:**
```kotlin
// Let exceptions propagate - caller will decide whether to retry
val rawResponse = hproseService?.runMApp<Any>(entry, params)
val ipArray = unwrapV2Response<List<String>>(rawResponse)
// ...
if (ipArray != null && ipArray.isNotEmpty()) {
    return tryIpAddresses(ipArray)
}
// Return null means: server responded successfully but no IPs found
return null
```

#### 2. Modified `getProviderIP()` (Line ~3481)
Now catches exceptions and propagates them to signal network errors:
```kotlin
try {
    val providerIP = _getProviderIP(mid)
    if (providerIP != null) {
        return providerIP
    }
    // null means: server responded but no IPs found (user not found)
} catch (e: Exception) {
    // Network error occurred - propagate exception to trigger retry
    Timber.tag("getProviderIP").w(e, "Network error in _getProviderIP, propagating")
    throw e
}
```

#### 3. Modified `resolveAndUpdateBaseUrl()` (Line ~3012)
Now handles both cases appropriately:
```kotlin
try {
    val providerIP = getProviderIP(user.mid)
    if (providerIP != null) {
        user.baseUrl = newBaseUrl
        // ... update NodePool
    } else {
        // null = user not found, NOT a retryable error
        Timber.tag("updateUserFromServer").w("⚠️ User not found or no IPs available")
        // Continue with null baseUrl - let calling code handle appropriately
    }
} catch (e: Exception) {
    // Exception = network error, SHOULD retry
    Timber.tag("updateUserFromServer").w(e, "🔴 Network error, attempt: $attempt")
    throw e  // Re-throw to trigger retry logic
}
```

### How It Works

**Scenario 1: Network Error (Should Retry)**
```
Attempt 1: Network error in _getProviderIP
         → Exception thrown
         → Caught in resolveAndUpdateBaseUrl
         → Re-thrown to trigger retry
         → originalBaseUrl restored
         → Retry scheduled

Attempt 2: Retry with preserved baseUrl
         → Either succeeds or fails appropriately
```

**Scenario 2: User Not Found (Should NOT Retry)**
```
Attempt 1: Server responds but returns empty IP array
         → _getProviderIP returns null (no exception)
         → Caught in resolveAndUpdateBaseUrl
         → Logs "user not found"
         → Continues with null baseUrl
         → Fails gracefully without retry
```

### Expected Log Output

**Network Error (Retries):**
```
🔴 Network error calling getProviderIP, attempt: 1/2
🔧 Restored originalBaseUrl after exception
⏳ Waiting 1s before retry
🔄 Retry attempt 2
```

**User Not Found (No Retry):**
```
🔍 Received IPs from server: null
⚠️ User not found or no IPs available
❌ NULL RESPONSE on final attempt
```

## Future Improvements

1. **Image Downloads**: Consider adding baseUrl refresh logic to image downloads
2. **Configurable Retries**: Make maxRetries configurable per call based on importance
3. **Circuit Breaker**: Implement circuit breaker pattern for persistent failures
4. **Metrics**: Track retry success/failure rates for monitoring

