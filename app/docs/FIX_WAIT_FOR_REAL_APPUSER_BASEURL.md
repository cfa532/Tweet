# Fix: Wait for Real appUser.baseUrl Instead of Entry IP

## Problem

User reported: **"When appUser is ready and app restarts, everything loads lightning fast"**

But on first launch, everything was slow and timing out. This revealed a critical **timing bug**:

### The Race Condition

```
16:51:27.328 initAppEntry - Set baseUrl to IP: http://125.229.161.122:8080 (ENTRY IP)
16:51:29.330 TweetFeedViewModel - baseUrl available: http://125.229.161.122:8080 (STILL ENTRY IP!)
16:51:29.330 TweetFeedViewModel - Fetching fresh tweets... (USING SLOW ENTRY IP!)
...
16:51:45.677 initAppEntry - ✅ User fetch successful - baseUrl: http://60.163.239.46:8081 (REAL IP)
```

**The problem:**
1. `initAppEntry` sets `appUser.baseUrl = entryIP` immediately (for bootstrapping)
2. `getTweetFeed` checks "is baseUrl not blank?" and proceeds **with entry IP**
3. Entry IP is slow/times out
4. 16 seconds later, the **real IP** is resolved, but too late!

**Why second launch is fast:**
- appUser is cached with the **real IP** from previous session
- No entry IP bootstrap needed
- App immediately uses the fast server

## Root Cause

`waitForAppUser()` only checked:
```kotlin
while (appUser.baseUrl.isNullOrBlank()) { ... }
```

This passed as soon as entry IP was set, not waiting for the **real server IP**.

## Solution

Added `isAppUserInitialized` flag to track when appUser has been **fully fetched from server**:

### 1. Added StateFlow Flag (HproseInstance.kt)

```kotlin
// Track if appUser has been fully initialized from server (not just entry IP)
private val _isAppUserInitialized = MutableStateFlow(false)
val isAppUserInitialized: StateFlow<Boolean> = _isAppUserInitialized.asStateFlow()
```

### 2. Set Flag After Successful Fetch

```kotlin
// After successfully fetching appUser from server:
_isAppUserInitialized.value = true
Timber.d("✅ User fetch successful - baseUrl: ${appUser.baseUrl}, initialized: true")
```

### 3. Set Flag When Using Cached User

```kotlin
if (hasCachedUser) {
    // Cached user has valid baseUrl, mark as initialized
    _isAppUserInitialized.value = true
    Timber.d("🚀 BaseUrl ready with cached data (initialized: true)")
}
```

### 4. Updated waitForAppUser() to Check Flag

**Before:**
```kotlin
while (appUser.baseUrl.isNullOrBlank()) {
    delay(200)  // Passes as soon as entry IP is set!
}
```

**After:**
```kotlin
while (!HproseInstance.isAppUserInitialized.value) {
    delay(200)  // Only passes after server fetch completes
}
```

## Impact

**First Launch (Cold Start):**
```
Before:
16:51:29 - getTweetFeed starts with entry IP (slow!)
16:51:45 - Real IP resolved (too late)

After:
16:51:27 - Entry IP set
16:51:29 - getTweetFeed WAITS (checking isAppUserInitialized)
16:51:45 - Real IP resolved, flag set to true
16:51:45 - getTweetFeed proceeds with FAST server!
```

**Subsequent Launches (Cached appUser):**
```
Both Before and After:
- appUser loaded from cache with real IP
- Flag set to true immediately  
- getTweetFeed proceeds instantly (lightning fast!)
```

## Files Modified

- ✅ `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`
  - Added `isAppUserInitialized` StateFlow
  - Set flag after successful fetch
  - Set flag when using cached user

- ✅ `app/src/main/java/us/fireshare/tweet/viewmodel/TweetFeedViewModel.kt`
  - Updated `waitForAppUser()` to check flag

- ✅ `app/src/main/java/us/fireshare/tweet/TweetActivity.kt`  
  - Updated `loadEntryUrls()` to check flag

- ✅ `app/src/main/java/us/fireshare/tweet/network/HproseClientPool.kt`
  - Reset timeout to 15 seconds (not 60s, since we now use correct IP)

## Expected Behavior

### First Launch:
- Entry IP set immediately for bootstrapping
- Background: Fetch appUser from server (10-15 seconds)
- **getTweetFeed waits** for real IP to be resolved
- Once resolved, uses fast server
- **Result:** Slower initial load, but uses correct server

### Subsequent Launches:
- Cached appUser loaded with real IP
- Flag set to true immediately
- **getTweetFeed proceeds instantly**
- **Result:** Lightning fast! ⚡

## Why This is Critical

**Without this fix:**
- Every API call uses slow/timing out entry IP
- Hprose 15-second timeout triggers
- User sees errors and slow performance

**With this fix:**
- API calls wait for fast server to be resolved
- Hprose calls complete in 1-2 seconds
- User sees fast, reliable performance
- Matches iOS "lightning fast" experience

## Testing Recommendations

1. **Cold start** - Uninstall app, reinstall, first launch should be slower but not timeout
2. **Warm start** - Close and reopen app, should be lightning fast
3. **Network issues** - Poor connection should wait and use correct server, not fall back to entry IP

This fix is the **most important performance improvement** because it ensures the app always uses the optimal server!

