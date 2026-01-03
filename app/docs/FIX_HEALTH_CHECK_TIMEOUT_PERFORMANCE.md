# Fix: Health Check Timeout Performance Issue

## Problem

Android app was significantly slower than iOS when performing network operations, especially during:
- Initial app startup
- Tweet feed loading
- Media uploads (IPFS)
- User profile fetching

User reported: **"iOS running lighting fast, timeout is not an issue there"**

## Root Cause

Android was using **3x longer timeouts** for HTTP health checks compared to iOS:

### iOS Health Check Timeouts
```swift
// HproseInstance.swift:1787
var request = URLRequest(url: baseURL, timeoutInterval: 5.0)
```
- HTTP HEAD request: **5 seconds**

### Android Health Check Timeouts (BEFORE)
```kotlin
// HproseInstance.kt:3769-3772
install(HttpTimeout) {
    requestTimeoutMillis = 15_000  // 15 seconds
    connectTimeoutMillis = 10_000  // 10 seconds
    socketTimeoutMillis = 15_000   // 15 seconds
}
```

## Impact Analysis

Health checks are performed in multiple places:
1. **NodePool IP verification** - When retrieving cached IPs from NodePool
2. **appUser health check** - When determining if appUser's server is reachable
3. **IP testing during resolution** - When testing multiple IPs returned from `get_node_ips` or `get_provider_ips`

### Example Scenario:
When fetching tweets after app startup:
- Check NodePool for appUser IP (1 health check)
- If failed, resolve new IP via `getProviderIP` (1 health check for appUser)
- Test 3-4 IPs from server (3-4 health checks)

**iOS timing:** 5s + 5s + (3 × 5s) = **25 seconds worst case**  
**Android timing (before):** 15s + 15s + (3 × 15s) = **75 seconds worst case**

In practice, when IPs are unreachable, Android users experienced **30-50 seconds longer delays** than iOS users for the same operations.

## Solution

**Matched Android health check timeouts to iOS implementation:**

### Android Health Check Timeouts (AFTER)
```kotlin
// HproseInstance.kt:3765-3774
/**
 * Dedicated HTTP client for health checks with shorter timeouts
 * Timeouts match iOS implementation (5 seconds) for consistent performance
 */
private val healthCheckHttpClient = HttpClient(CIO) {
    engine {
        maxConnectionsCount = 100
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 5_000  // 5 seconds (matches iOS)
        connectTimeoutMillis = 3_000  // 3 seconds to connect (aggressive)
        socketTimeoutMillis = 5_000   // 5 seconds (matches iOS)
    }
}
```

### Changes:
- **requestTimeoutMillis**: `15_000` → `5_000` (15s → 5s)
- **connectTimeoutMillis**: `10_000` → `3_000` (10s → 3s)
- **socketTimeoutMillis**: `15_000` → `5_000` (15s → 5s)

## Benefits

1. **Faster failure detection** - Dead IPs fail in 5 seconds instead of 15
2. **Quicker retry cycles** - Retry mechanism can try fallback strategies sooner
3. **iOS parity** - Both platforms have consistent performance characteristics
4. **Better UX** - Users experience faster responses, especially when networks are unstable

## Testing Recommendations

Test in these scenarios:
1. **Poor network conditions** - Verify app doesn't become unresponsive
2. **Server unavailable** - Confirm timeouts trigger appropriately
3. **Multiple IP testing** - Ensure parallel health checks complete quickly
4. **Normal operations** - Verify no regressions in stable network conditions

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (lines 3765-3774)
- `app/src/main/java/us/fireshare/tweet/network/HproseClientPool.kt` (separate timeout for API calls - 5 minutes, unchanged)

## Related Fixes

This fix is part of a series of performance improvements:
1. ✅ **FIX_HPROSE_CLIENT_TIMEOUT_TOO_SHORT.md** - Increased API call timeout from 10s to 5 minutes
2. ✅ **FIX_APPID_EMPTY_TIMING_ISSUE.md** - Fixed appId initialization preventing API failures
3. ✅ **FIX_APPUSER_BASEURL_DEADLOCK.md** - Fixed app startup deadlock
4. ✅ **This fix** - Reduced health check timeouts to match iOS

## Notes

- The `DEFAULT_CLIENT_TIMEOUT` (5 minutes) in `HproseClientPool.kt` is **separate** and used for actual API calls, not health checks
- Health checks use HTTP HEAD requests, which should be fast on healthy servers
- The 3-second connect timeout is aggressive but necessary for quick failure detection
- These timeouts only affect health checks, not data transfer operations

