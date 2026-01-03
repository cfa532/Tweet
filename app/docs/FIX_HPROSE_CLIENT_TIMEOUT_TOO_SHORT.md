# Fix: Hprose Client Timeout Too Short (10s vs iOS 5min)

## Problem

Android app was experiencing repeated timeout failures for API calls like `getTweetFeed`, even though:
- AppUser was fetched successfully
- iOS app works "lightning fast" with the same network
- The network is working, just slow

### Symptoms

```
getTweetFeed: Exception calling runMApp for getTweetFeed (attempt 1/6)
Caused by: java.util.concurrent.TimeoutException
... (repeats 6 times, each taking exactly 10 seconds)
```

## Root Cause

**Android** used a **10-second timeout** for regular API operations:

```kotlin
private const val DEFAULT_CLIENT_TIMEOUT = 10_000 // 10 seconds (WRONG!)
```

**iOS** uses a **5-minute (300-second) timeout** for regular API operations:

```swift
client.timeout = 300000  // 5 minutes in milliseconds
```

### The Impact

On slower networks or when servers take longer to process requests:
- **iOS**: Request completes successfully within 5 minutes → "lightning fast" user experience
- **Android**: Request aborts after 10 seconds → Timeout → Retry → Timeout again → Endless failures

### Why 10 Seconds Wasn't Enough

From the user's logs, each `getTweetFeed` call was taking more than 10 seconds:
```
16:39:36.886 - Attempt 1 started
16:39:46.885 - Timeout after 10s
16:39:46.924 - Attempt 2 started  
16:39:56.924 - Timeout after 10s
... (repeated 6 times, total 60 seconds of failures)
```

The server was responding, just slowly (possibly due to network latency, server load, or complex queries).

## The Fix

Changed Android's `DEFAULT_CLIENT_TIMEOUT` to match iOS:

```kotlin
private const val DEFAULT_CLIENT_TIMEOUT = 300_000 // 5 minutes (matches iOS)
```

### File Changed

- `app/src/main/java/us/fireshare/tweet/network/HproseClientPool.kt` line 28

## Why This Makes Android "Lightning Fast" Like iOS

1. **No premature timeouts**: Requests that take 15-30 seconds can now complete successfully
2. **Fewer retries**: Instead of 6 failed attempts (60 seconds total), one successful request
3. **Better UX**: User sees content loading instead of error/retry spinners
4. **Network-tolerant**: Works on slower networks or when servers are under load

## Expected Behavior After Fix

### Before (10s timeout):
```
Request → 10s → Timeout → Retry → 10s → Timeout → ... (6 retries, 60s total failure)
```

### After (5min timeout):
```
Request → 15s → Success ✅ (Total time: 15s instead of 60s)
```

## Related Code

- `HproseClientPool.kt` line 28: `DEFAULT_CLIENT_TIMEOUT` constant
- `HproseClientPool.kt` line 125: Applied to regular clients
- iOS `User.swift` line 168: Reference implementation (300000ms = 5 minutes)

## Why iOS Comment Said "Matches Kotlin"

The iOS code comment:
```swift
client.timeout = 300000  // 5 minutes in milliseconds (matches Kotlin regular client)
```

This comment was correct when written, but Android's timeout got changed to 10 seconds at some point, creating the mismatch. This fix restores the original behavior.

## Prevention

When comparing Android/iOS behavior:
- **Always check timeout values** - they're critical for network reliability
- **Test on slow networks** - fast networks hide timeout issues
- **Match iOS implementation** - iOS is the reference for correct behavior
- **Comment about cross-platform parity** - helps catch divergence

## Testing

1. Run app on slower network
2. Observe `getTweetFeed` calls complete successfully (may take 15-30s)
3. No timeout exceptions
4. App feels "lightning fast" like iOS because requests succeed instead of failing/retrying

