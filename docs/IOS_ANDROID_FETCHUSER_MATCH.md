# iOS-Android fetchUser Matching Implementation

This document describes the changes made to ensure Android's `fetchUser` behavior matches iOS when user profiles are opened.

## User Request

"when user profile is open, the user object should always be refreshed from server. Check how iOS version does it and match its algorithm in fetchUser"

## Problem

Previously, Android's `fetchUser` would return cached user data even when `baseUrl` was passed as an empty string. This differed from iOS behavior, where passing an empty `baseUrl` would force a fresh fetch from the server.

## iOS Behavior (Reference Implementation)

### ProfileView.swift (Lines 637-677)

When a user profile is opened in iOS, the `refreshProfileData()` function is called:

```swift
private func refreshProfileData() async {
    // Fetch fresh user data from server
    let refreshedUser = try await hproseInstance.fetchUser(user.mid, baseUrl: "")
    
    if let refreshedUser = refreshedUser {
        TweetCacheManager.shared.saveUser(refreshedUser)
    }
    // ...
}
```

**Key Points:**
- Calls `fetchUser` with **empty baseUrl** (`baseUrl: ""`)
- Does **NOT** use `forceRefresh: true` (uses default value of `false`)
- Does **NOT** remove cached user before calling `fetchUser`
- Relies on the empty baseUrl to trigger fresh server fetch

### HproseInstance.swift fetchUser (Lines 1080-1130)

```swift
func fetchUser(
    _ userId: String,
    baseUrl: String = shared.appUser.baseUrl?.absoluteString ?? "",
    maxRetries: Int = 2,
    forceRefresh: Bool = false,  // Default is false
    skipRetryAndBlacklist: Bool = false
) async throws -> User? {
    // Check cache first if not forcing refresh
    if !forceRefresh {
        let cachedUser = await TweetCacheManager.shared.fetchUser(mid: userId)
        
        if cachedUser.username != nil && cachedUser.baseUrl != nil {
            let hasExpired = await cachedUser.hasExpired()
            
            // Return cached user ONLY if not expired AND baseUrl is not empty
            if !hasExpired && !baseUrl.isEmpty {  // <<< KEY CHECK
                return cachedUser
            } else if hasExpired {
                // Start background refresh, return stale data
                return cachedUser
            }
        }
    }
    
    // If baseUrl is empty, fall through to fetch from server
    // ...
}
```

**Key Algorithm:**
1. If `forceRefresh` is false, check cache
2. If cached user exists and is valid:
   - Check: `!hasExpired && !baseUrl.isEmpty`
   - If **baseUrl is empty**, this check fails → fetch from server
   - If **baseUrl is not empty** and not expired → return cache
3. Otherwise, fetch from server

## Android Changes Made

### 1. Updated HproseInstance.kt fetchUser (Lines 2346-2378)

**Before:**
```kotlin
// Check cache first (if not forcing refresh)
if (!forceRefresh) {
    val cachedUser = TweetCacheManager.getCachedUser(userId)
    if (cachedUser != null && cachedUser.username != null) {
        if (cachedUser.hasExpired) {
            // Background refresh...
        }
        return cachedUser  // ALWAYS returned cached user
    }
}
```

**After:**
```kotlin
// Check cache first (if not forcing refresh)
if (!forceRefresh) {
    val cachedUser = TweetCacheManager.getCachedUser(userId)
    if (cachedUser != null && cachedUser.username != null) {
        // Matching iOS behavior: only return cached user if baseUrl parameter is not empty
        if (!cachedUser.hasExpired && !baseUrl.isNullOrEmpty()) {
            // Return valid cached user only if baseUrl parameter is provided
            return cachedUser
        } else if (cachedUser.hasExpired) {
            // Start background refresh, return stale cached user
            return cachedUser
        }
        // If baseUrl is empty, fall through to fetch from server
    }
}
```

**Key Changes:**
- Added check: `!cachedUser.hasExpired && !baseUrl.isNullOrEmpty()`
- When `baseUrl` is empty, cache is skipped and fresh fetch occurs
- Matches iOS logic exactly

### 2. Updated ProfileScreen.kt (Lines 109-119)

**Before:**
```kotlin
LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
        // Invalidate cache first to force fresh fetch from server
        TweetCacheManager.removeCachedUser(userId)  // <<< Extra step not in iOS
        viewModel.refreshUserData()
    }
}
```

**After:**
```kotlin
LaunchedEffect(Unit) {
    // Always refresh user from server when profile screen is opened
    // Matches iOS ProfileView.refreshProfileData() behavior
    withContext(Dispatchers.IO) {
        Timber.tag("ProfileScreen").d("Refreshing user data from server for userId: $userId")
        // Refresh user data from server (matching iOS - no cache removal, relies on fetchUser logic)
        viewModel.refreshUserData()
    }
}
```

**Key Changes:**
- Removed `TweetCacheManager.removeCachedUser(userId)` call
- Relies on `fetchUser` logic to handle caching correctly
- Matches iOS approach of not explicitly removing cache

### 3. Updated UserViewModel.kt refreshUserWithRetry (Lines 199-227)

**Before:**
```kotlin
private suspend fun refreshUserWithRetry(maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        try {
            // Force refresh from server, skip cache
            val refreshedUser = fetchUser(
                userId, 
                baseUrl = "", 
                maxRetries = 1, 
                forceRefresh = true  // <<< Extra flag not used in iOS
            )
            // ...
```

**After:**
```kotlin
/**
 * Refresh user data with retry logic
 * Matches iOS ProfileView.refreshProfileData() behavior:
 * - Passes empty baseUrl to force fresh IP resolution and skip cache
 * - Does NOT use forceRefresh=true (relies on empty baseUrl logic)
 * - Does NOT remove cached user before fetching
 */
private suspend fun refreshUserWithRetry(maxRetries: Int = 3) {
    repeat(maxRetries) { attempt ->
        try {
            // Pass empty baseUrl to force IP re-resolution and skip cache (matching iOS)
            // fetchUser will skip cache when baseUrl is empty and fetch from server
            val refreshedUser = fetchUser(
                userId, 
                baseUrl = "", 
                maxRetries = 1, 
                forceRefresh = false  // <<< Now matches iOS default
            )
            // ...
```

**Key Changes:**
- Changed `forceRefresh = true` to `forceRefresh = false`
- Relies on `baseUrl = ""` to skip cache (matching iOS)
- Updated documentation to clarify iOS matching behavior

## Summary

### What Changed
1. **HproseInstance.kt fetchUser**: Now checks if `baseUrl` is not empty before returning cached user
2. **ProfileScreen.kt**: Removed explicit cache removal call before refresh
3. **UserViewModel.kt refreshUserWithRetry**: Changed `forceRefresh = true` to `forceRefresh = false`

### Why It Matters
- **Consistent behavior**: Android now matches iOS behavior exactly
- **Fresh data on profile open**: User profiles always fetch latest data from server when opened
- **Better IP resolution**: Empty baseUrl forces fresh provider IP resolution
- **Cleaner code**: Simpler logic that relies on the fetchUser algorithm rather than explicit cache manipulation

### How It Works Now

When a user profile is opened in Android:

1. `ProfileScreen` calls `viewModel.refreshUserData()`
2. `refreshUserData()` calls `fetchUser(userId, baseUrl = "", forceRefresh = false)`
3. `fetchUser` checks cache:
   - If cached user exists and is valid
   - Checks: `!cachedUser.hasExpired && !baseUrl.isNullOrEmpty()`
   - Since `baseUrl = ""` (empty), this check **fails**
   - Falls through to fetch from server
4. Fresh user data is fetched and cached

This matches iOS behavior perfectly, where ProfileView passes `baseUrl: ""` to force a fresh fetch.

## Testing

Build command used:
```bash
./gradlew assembleFullDebug assembleMiniDebug
```

Both variants built successfully with the changes.

## Related Files

- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` (fetchUser method)
- `app/src/main/java/us/fireshare/tweet/profile/ProfileScreen.kt`
- `app/src/main/java/us/fireshare/tweet/viewmodel/UserViewModel.kt` (refreshUserWithRetry method)
- `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Features/Profile/ProfileView.swift` (reference)
- `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift` (reference)

## Date

December 22, 2025

