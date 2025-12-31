# CRITICAL FIX: fetchUser baseUrl Parameter Handling

## Issue Description

**Symptoms:**
- Tweet authors not loading (all showing guest user)
- User avatars on toolbar not updating
- Profile pages showing incomplete data

**Root Cause:**
The Android implementation incorrectly handled the `baseUrl` parameter in `fetchUser()`, causing it to overwrite each user's actual server baseUrl with `appUser`'s baseUrl.

## The Problem

### Incorrect Android Implementation (BEFORE)

```kotlin
suspend fun fetchUser(userId: MimeiId?, baseUrl: String? = appUser.baseUrl, ...) {
    val user = getUserInstance(userId)
    
    // ❌ BUG: Always overwrites user's baseUrl!
    user.baseUrl = if (baseUrl.isNullOrEmpty()) {
        null
    } else {
        baseUrl  // This is appUser.baseUrl by default!
    }
    
    return performUserUpdate(userId, user, ...)
}
```

**What Happened:**
1. Call `fetchUser(authorId)` without specifying baseUrl
2. baseUrl defaults to `appUser.baseUrl` (e.g., `"http://125.229.161.122:8080"`)
3. Code sets `author.baseUrl = appUser.baseUrl`
4. **Author and appUser are on DIFFERENT servers/nodes!**
5. Try to fetch author data from appUser's server → **FAILS!**
6. Author appears as guest user

### Correct iOS Implementation (REFERENCE)

```swift
func fetchUser(
    _ userId: String,
    baseUrl: String = shared.appUser.baseUrl?.absoluteString ?? "",
    maxRetries: Int = 2,
    forceRefresh: Bool = false,
    skipRetryAndBlacklist: Bool = false
) async throws -> User? {
    let user = User.getInstance(mid: userId)
    
    // ✅ CORRECT: Only apply baseUrl if explicitly provided
    if !baseUrl.isEmpty {
        if let url = URL(string: baseUrl) {
            await applyBaseUrlIfNeeded(user, url: url, reason: "fetchUser initial setup")
        }
    }
    // If baseUrl is empty or default, preserve user's existing baseUrl
    
    return try await performUserUpdate(user, maxRetries: maxRetries, ...)
}
```

**Key Insight:**
iOS **only** sets user's baseUrl when `baseUrl` parameter is **explicitly and intentionally** provided. Otherwise, it preserves the user's cached baseUrl from their singleton.

## The Fix

### Correct Android Implementation (AFTER)

```kotlin
suspend fun fetchUser(
    userId: MimeiId?, 
    baseUrl: String? = appUser.baseUrl,  // This is just a HINT
    maxRetries: Int = 2, 
    forceRefresh: Boolean = false, 
    skipRetryAndBlacklist: Boolean = false
): User? {
    val user = getUserInstance(userId)

    // ✅ CORRECT: baseUrl parameter is a HINT for cache logic, NOT the actual baseUrl!
    // Each user has their own baseUrl (their own server/node).
    // - If baseUrl param is "" (empty): forces IP resolution (bypasses cache)
    // - If baseUrl param is null/default: use normal cache logic
    // - User's actual baseUrl comes from their singleton or gets resolved
    //
    // We ONLY clear user.baseUrl if explicitly forcing to empty (triggers resolution)
    if (baseUrl != null && baseUrl.isEmpty()) {
        // Empty string explicitly passed - clear to force IP resolution
        user.baseUrl = null
    }
    // For all other cases (null, appUser.baseUrl default, or specific URL), 
    // keep user's existing baseUrl - don't overwrite!
    
    return performUserUpdate(userId, user, maxRetries, skipRetryAndBlacklist, "getUser", baseUrl)
}
```

## Understanding the baseUrl Parameter

The `baseUrl` parameter in `fetchUser()` is **NOT** the baseUrl to use for fetching. It's a **control hint** for cache behavior:

| Parameter Value | Meaning | Behavior |
|----------------|---------|----------|
| `""` (empty string) | Force fresh IP resolution | Clear user.baseUrl → triggers `getProviderIP()` |
| `null` or default (`appUser.baseUrl`) | Use normal cache logic | Keep user's existing cached baseUrl |
| Specific URL | Reserved for future use | Keep user's existing cached baseUrl |

### Usage Examples

```kotlin
// Normal fetch - uses user's cached baseUrl (or resolves if first time)
val author = fetchUser(authorId)  // baseUrl = appUser.baseUrl (default, ignored)

// Force IP resolution - clears user.baseUrl to trigger getProviderIP()
val user = fetchUser(userId, baseUrl = "")  // Forces fresh IP lookup

// Login scenario - force fresh IP to ensure healthy server
val loggedInUser = fetchUser(userId, baseUrl = "", forceRefresh = true)
```

## Why This Design?

### 1. **Each User Has Their Own Server Node**

In a distributed system:
- `appUser` lives on node A (`http://125.229.161.122:8080`)
- `tweetAuthor` lives on node B (`http://60.163.239.46:8002`)
- `follower` lives on node C (`http://54.255.233.192:8080`)

Each user's `baseUrl` points to **their own server**. We must NEVER overwrite one user's baseUrl with another's!

### 2. **User Singleton Maintains State**

```kotlin
// User.kt
companion object {
    private val userInstances = mutableMapOf<String, User>()
    
    fun getInstance(mid: String): User {
        return userInstances.getOrPut(mid) { User(mid = mid) }
    }
}
```

Each user has a **persistent singleton** that remembers their baseUrl across fetches. This is the **authoritative source** for their server location.

### 3. **baseUrl Parameter is for Cache Control**

The parameter name is misleading - it should really be called `cacheControl` or `forceRefresh`:

```kotlin
// What it ACTUALLY controls:
if (baseUrl.isEmpty()) {
    // Skip cache even if valid, force IP resolution
} else {
    // Use cache if valid
}
```

## Related Components Updated

1. **`performUserUpdate()`** - Added `baseUrlHint` parameter
2. **`updateUserFromServerWithRetry()`** - Added `baseUrlHint` parameter
3. **`startBackgroundRefresh()`** - Added `baseUrlHint` parameter
4. **`resolveAndUpdateBaseUrl()`** - Uses hint to determine `forceFreshIP`

## Testing

### Before Fix
```
✗ Tweet authors show as guest user
✗ User avatars don't load
✗ Profile data incomplete
✗ Comments show wrong authors
```

### After Fix
```
✓ Tweet authors load correctly
✓ User avatars display
✓ Profile data complete
✓ Comments show correct authors
✓ Each user fetches from their own server
```

## Key Takeaways

1. **Never overwrite a user's baseUrl** with appUser's baseUrl
2. **Preserve user singleton state** - it's the source of truth
3. **Empty string forces IP resolution** - null/default preserves state
4. **Each user has their own server** - respect the distributed architecture
5. **Match iOS behavior** - always reference the working implementation

## See Also

- NodePool implementation for persistent IP management
- getProviderIP() for intelligent server discovery
- resolveAndUpdateBaseUrl() for IP resolution logic

