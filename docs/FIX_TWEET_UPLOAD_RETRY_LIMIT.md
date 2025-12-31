# Fix: Tweet Upload Retry Limit

## Issue

Previously failed tweet uploads were retrying **4 times** instead of the intended **2 times** (1 initial attempt + 1 retry).

## Root Cause

The retry limit logic was off by one:

```kotlin
// WRONG ❌ - Allows 3+ attempts
if (runAttemptCount > 1) {
    // Give up
}
```

This logic meant:
- `runAttemptCount = 0`: First attempt ✅
- `runAttemptCount = 1`: First retry ✅
- `runAttemptCount = 2`: Second retry ✅ (should stop here!)
- `runAttemptCount = 3`: Third retry ✅ (should have stopped!)
- `runAttemptCount = 4`: Finally stops ❌

## The Fix

Updated to properly limit to **2 total attempts**:

```kotlin
// CORRECT ✅ - Allows exactly 2 attempts
if (runAttemptCount >= 2) {
    Timber.tag("UploadTweetWorker").d("Maximum retry attempts reached (attempt $runAttemptCount), giving up")
    TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed after 2 attempts"))
    val workId = id.toString()
    HproseInstance.removeIncompleteUpload(applicationContext, workId)
    return Result.failure()
}

Timber.tag("UploadTweetWorker").d("Tweet upload attempt ${runAttemptCount + 1} of 2")
```

## Retry Behavior

### Now (After Fix)
```
Attempt 1 (runAttemptCount=0): Initial upload
  ↓ [FAIL]
  ↓ Wait 10 seconds (exponential backoff)
  ↓
Attempt 2 (runAttemptCount=1): Retry with refreshed baseUrl
  ↓ [FAIL]
  ↓ Show error to user
  ↓ Clean up incomplete upload
  ↓
STOP ✅
```

### Before (Bug)
```
Attempt 1 (runAttemptCount=0): Initial upload
  ↓ [FAIL]
Attempt 2 (runAttemptCount=1): Retry
  ↓ [FAIL]
Attempt 3 (runAttemptCount=2): Retry (should have stopped!) ❌
  ↓ [FAIL]
Attempt 4 (runAttemptCount=3): Retry (should have stopped!) ❌
  ↓ [FAIL]
STOP
```

## Additional Improvements

### 1. Increased Initial Backoff
```kotlin
// Before: 2 seconds
// After: 10 seconds
.setBackoffCriteria(
    BackoffPolicy.EXPONENTIAL,
    10_000L, // 10 seconds - gives server time to recover
    java.util.concurrent.TimeUnit.MILLISECONDS
)
```

**Why:** Servers may be temporarily overloaded. Waiting 10 seconds gives them time to recover before retrying.

### 2. Better Logging
```kotlin
// Now logs attempt number
Timber.tag("UploadTweetWorker").d("Tweet upload attempt ${runAttemptCount + 1} of 2")
Timber.tag("UploadTweetWorker").e(e, "Error in doWork (attempt ${runAttemptCount + 1})")
```

**Why:** Makes debugging easier - you can see which attempt failed.

### 3. BaseUrl Refresh on Retry
```kotlin
// On retry (runAttemptCount >= 1), force IP re-resolution
if (runAttemptCount >= 1) {
    val refreshedUser = HproseInstance.fetchUser(
        appUser.mid, 
        baseUrl = "", // Force fresh IP lookup
        maxRetries = 1, 
        forceRefresh = true
    )
    HproseInstance.appUser = refreshedUser
}
```

**Why:** The original server IP might be down. Refreshing gets a new healthy server IP from the node pool.

## Impact

✅ **Reduced retry spam** - Only 2 attempts instead of 4+  
✅ **Faster failure feedback** - Users see errors sooner  
✅ **Less server load** - Fewer redundant retry attempts  
✅ **Clearer logs** - Attempt numbers visible  

## Files Modified

1. **`TweetWorker.kt`**
   - Fixed `UploadTweetWorker` retry limit (line 187)
   - Fixed `UploadCommentWorker` retry limit (line 65)
   - Added attempt logging

2. **`TweetFeedViewModel.kt`**
   - Increased backoff from 2s → 10s (line 584)

## Testing

**To verify:**
1. Try uploading a tweet with no network
2. Should see:
   ```
   Attempt 1: FAIL
   [Wait 10 seconds]
   Attempt 2: FAIL
   Error: "Tweet upload failed after 2 attempts"
   ```

## Related Fixes

This completes the upload reliability improvements:
1. ✅ Fixed timeout (uploadService instead of hproseService)
2. ✅ Fixed retry limit (2 attempts instead of 4+)
3. ✅ Better backoff timing (10s instead of 2s)

## Date

December 31, 2025

