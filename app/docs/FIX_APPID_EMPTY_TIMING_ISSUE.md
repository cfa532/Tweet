# Fix: AppId Empty/Timing Issue Causing "User Not Found" Errors

## Problem

When the app starts, sometimes the API returns "User not found" error even though the user exists on the server (iOS works fine with the same userId). The root cause was that Android was sending an **empty `aid` (appId) parameter** in API requests.

### Symptoms

```
unwrapV2Response: API returned error: User not found
```

Even though:
- iOS app works perfectly with the same userId
- The userId exists on the server
- Android's `BuildConfig.APP_ID` is correctly set

## Root Cause

Android's `findEntryIP()` was not properly handling empty `mid` values from the server response, unlike iOS which explicitly checks for empty strings.

### The Bug

**Android** (before fix) - line 437-445:
```kotlin
val serverMid = paramMap["mid"]?.toString()

_appId = if (BuildConfig.DEBUG) {
    BuildConfig.APP_ID
} else {
    serverMid ?: BuildConfig.APP_ID  // ← BUG: Only checks null, not empty!
}
```

**Problem**: If the server returns `mid: ""` (empty string), Android uses the empty string instead of falling back to `BuildConfig.APP_ID`. The `?:` operator only checks for `null`, not for blank/empty strings.

**iOS** (correct behavior) - line 458:
```swift
if let serverAppId = paramData["mid"] as? String, !serverAppId.isEmpty {
    appId = serverAppId
    print("Updated appId from server: \(appId)")
} else {
    print("Server did not provide appId, keeping AppConfig value: \(appId)")
}
```

**Correct**: iOS checks both null AND empty (`!serverAppId.isEmpty`) before updating.

## The Fix

Changed Android to match iOS behavior by checking `isNullOrBlank()`:

```kotlin
val serverMid = paramMap["mid"]?.toString()

_appId = if (BuildConfig.DEBUG) {
    BuildConfig.APP_ID
} else {
    // Use server's mid only if not null/empty (matching iOS behavior)
    if (!serverMid.isNullOrBlank()) {
        serverMid
    } else {
        Timber.tag("findEntryIP").d("Server mid is null/empty, using BuildConfig.APP_ID")
        BuildConfig.APP_ID
    }
}
```

### Enhanced Logging

Also improved logging to show both values:

```kotlin
Timber.tag("findEntryIP").d("Build type: ${...}, Using APP_ID: $_appId (serverMid: '${serverMid ?: "null"}')")
```

## Why This Fixes the Issue

1. **iOS Behavior Match**: Android now matches iOS's logic exactly
2. **Empty String Protection**: `.isNullOrBlank()` checks for:
   - `null`
   - Empty string `""`
   - Whitespace-only strings `"   "`
3. **Always Valid AppId**: `_appId` is guaranteed to never be empty:
   - Either has valid `serverMid` from server
   - Or falls back to `BuildConfig.APP_ID`

## Timeline of Events (Fixed)

### Before Fix:
```
1. findEntryIP() parses HTML response
2. Server returns: {mid: "", addrs: [...]}
3. Android: serverMid = "" (not null, so ?: doesn't trigger)
4. Android: _appId = "" (WRONG!)
5. get_user API called with: {aid: "", userid: "..."}
6. Server: "User not found" (because aid is empty)
```

### After Fix:
```
1. findEntryIP() parses HTML response
2. Server returns: {mid: "", addrs: [...]}
3. Android: serverMid = "" 
4. Android: !serverMid.isNullOrBlank() = false (triggers fallback)
5. Android: _appId = BuildConfig.APP_ID (CORRECT!)
6. get_user API called with: {aid: "heWgeGkeBX2gaENbIBS_Iy1mdTS", userid: "..."}
7. Server: Returns user data successfully ✅
```

## Testing

1. **Verify logs** show correct appId:
```
findEntryIP: Using APP_ID: 'heWgeGkeBX2gaENbIBS_Iy1mdTS' (serverMid: '')
```

2. **Verify API calls** succeed:
```
updateUserFromServer: get_user rawResponse received: Map
(No "User not found" error)
```

3. **App startup** should be "lightning fast" as user mentioned

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` line 434-452: Fixed appId parsing
- `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift` line 458: iOS reference implementation
- `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Utils/Gadget.swift` line 239-244: iOS paramMap extraction

## Prevention

When comparing with iOS:
- **Always check for empty strings**, not just null
- **Use `.isNullOrBlank()`** instead of `?: ` when dealing with String fallbacks
- **Match iOS behavior exactly** when implementing server communication logic
- **Test with empty string responses** from server, not just null

## Additional Changes

Also fixed the baseUrl deadlock issue (separate fix) where `appUser.baseUrl` was being cleared when user fetch failed, preventing the app from making any further API calls.

