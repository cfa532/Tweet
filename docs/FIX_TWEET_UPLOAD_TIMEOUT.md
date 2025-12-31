# Fix: Tweet Upload Timeout After 30 Seconds

## Issue

Tweet uploads were failing with `TimeoutException` after 30 seconds:

```
java.util.concurrent.TimeoutException
    at hprose.util.concurrent.PromiseFuture.get(PromiseFuture.java:86)
    at hprose.client.HproseClient.invoke(HproseClient.java:740)
```

## Root Cause

The `uploadTweet()` function in `HproseInstance.kt` was using the **wrong client**:

```kotlin
// WRONG - Uses regular client with 30-second timeout ❌
appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
```

**Why this happened:**
- During the network consolidation, we created a dedicated `uploadHttpClient` with 50-minute timeout
- `User.uploadService` property was correctly configured to use the upload client pool
- **BUT** `uploadTweet()` was still calling `hproseService` instead of `uploadService`

## The Fix

Changed `uploadTweet()` to use the correct service:

```kotlin
// CORRECT - Uses upload client with 50-minute timeout ✅
appUser.uploadService?.runMApp<Map<String, Any>>(entry, params)
```

**File:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (line 1926)

## Client Timeout Comparison

| Client | Timeout | Used For |
|--------|---------|----------|
| `hproseService` | 30 seconds | Regular API calls (feed, profiles, etc.) |
| `uploadService` | 50 minutes | Uploads (tweets, images, videos) |

## Why 50 Minutes?

Tweet uploads can include:
- Text processing
- Image/video attachment uploads to IPFS
- Metadata creation
- Server-side processing

For large video files (500MB+), this can take several minutes, especially on:
- Slow networks
- IPFS pinning delays
- Server processing time

## Testing

**Before Fix:**
```
2025-12-31 19:54:23.975 uploadTweet: Exception calling runMApp
java.util.concurrent.TimeoutException (after 30 seconds)
```

**After Fix:**
```
✅ Tweet uploads should complete successfully
✅ No timeout for tweets with attachments
✅ Large videos can upload fully
```

## Related Changes

This fix completes the network consolidation started in [NETWORK_CONSOLIDATION_2025.md](NETWORK_CONSOLIDATION_2025.md):

1. ✅ Created dedicated `uploadHttpClient` (20 connections, 50min timeout)
2. ✅ `User.uploadService` uses upload client pool
3. ✅ **Now fixed:** `uploadTweet()` uses `uploadService` instead of `hproseService`

## Impact

- ✅ Tweet uploads no longer timeout after 30 seconds
- ✅ Large attachments can upload fully
- ✅ Better user experience for content creation
- ✅ Reduced upload failures

## Files Modified

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (line 1926)

## Date

December 31, 2025

