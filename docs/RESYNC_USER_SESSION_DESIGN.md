# Resync User Session Design

## Overview

The `resync_user` API is called once per app session when a user profile is opened. This triggers a backend operation to refresh the user's data on the server side, ensuring data consistency across distributed nodes.

## iOS Reference Implementation

Location: `/Tweet-iOS/Sources/Features/Profile/ProfileView.swift`

```swift
// Track users that have been resynced this app session
private static var resyncedUsersThisSession: Set<String> = []
private static let resyncLock = NSLock()

// When profile opens
let shouldResync = Self.resyncLock.withLock {
    if Self.resyncedUsersThisSession.contains(userId) {
        return false
    }
    Self.resyncedUsersThisSession.insert(userId)
    return true
}

if shouldResync {
    Task.detached {
        let resyncedUser = try await hproseInstance.resyncUser(userId: userId)
        TweetCacheManager.shared.saveUser(resyncedUser)
    }
}
```

## Android Implementation

### 1. Session Tracking (HproseInstance.kt)

```kotlin
object HproseInstance {
    /**
     * Track users that have been resynced this app session to avoid redundant operations
     * Matches iOS ProfileView.resyncedUsersThisSession behavior
     */
    private val resyncedUsersThisSession = mutableSetOf<MimeiId>()
    private val resyncLock = Any()
    
    /**
     * Check if user should be resynced this session and mark as resynced if so.
     * Thread-safe check using synchronized block.
     */
    fun shouldResyncUser(userId: MimeiId): Boolean {
        return synchronized(resyncLock) {
            if (resyncedUsersThisSession.contains(userId)) {
                false
            } else {
                resyncedUsersThisSession.add(userId)
                true
            }
        }
    }
}
```

### 2. API Call (HproseInstance.kt)

```kotlin
/**
 * Resync user data on the server - matches iOS ProfileView behavior.
 * This triggers a backend operation to refresh the user's data on the server side.
 * Should be called once per app session when a user profile is opened.
 */
suspend fun resyncUser(userId: MimeiId): User? {
    return try {
        val user = getUserInstance(userId)
        
        // If user doesn't have a baseUrl, fetch it first
        if (user.baseUrl.isNullOrBlank()) {
            fetchUser(userId, baseUrl = "")
        }
        
        val entry = "resync_user"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "version" to "v2",
            "userid" to userId
        )
        
        // Use the target user's hproseService (with their baseUrl)
        val service = user.hproseService ?: appUser.hproseService
        
        // Make the API call on IO dispatcher
        val rawResponse = withContext(Dispatchers.IO) {
            service.runMApp<Any>(entry, params)
        }
        
        // Parse response and update user properties
        val userData = (rawResponse as? Map<*, *>) as? Map<String, Any>
        
        user.name = userData["name"] as? String
        user.username = userData["username"] as? String
        user.email = userData["email"] as? String
        user.profile = userData["profile"] as? String
        user.avatar = userData["avatar"] as? String
        
        // Update counts (non-nullable Int properties)
        (userData["tweetCount"] as? Number)?.let { user.tweetCount = it.toInt() }
        (userData["followingCount"] as? Number)?.let { user.followingCount = it.toInt() }
        (userData["followersCount"] as? Number)?.let { user.followersCount = it.toInt() }
        (userData["bookmarksCount"] as? Number)?.let { user.bookmarksCount = it.toInt() }
        (userData["favoritesCount"] as? Number)?.let { user.favoritesCount = it.toInt() }
        (userData["commentsCount"] as? Number)?.let { user.commentsCount = it.toInt() }
        
        // Update cloudDrivePort if provided
        (userData["cloudDrivePort"] as? Number)?.let { port ->
            user.cloudDrivePort = port.toInt()
        }
        
        // Save updated user to cache
        TweetCacheManager.saveUser(user)
        
        user
    } catch (e: Exception) {
        Timber.tag("resyncUser").e(e, "Failed to resync user $userId")
        null
    }
}
```

### 3. UI Integration (ProfileScreen.kt)

```kotlin
// Resync user data on server in background (long-running operation)
// Only run once per app session per user to avoid redundant expensive operations
LaunchedEffect(userId) {
    val shouldResync = HproseInstance.shouldResyncUser(userId)
    
    if (shouldResync) {
        withContext(Dispatchers.IO) {
            try {
                val resyncedUser = HproseInstance.resyncUser(userId)
                if (resyncedUser != null) {
                    TweetCacheManager.saveUser(resyncedUser)
                    Timber.tag("ProfileScreen").d("✅ Successfully resynced user $userId on server")
                }
            } catch (e: Exception) {
                Timber.tag("ProfileScreen").e(e, "Failed to resync user $userId")
            }
        }
    } else {
        Timber.tag("ProfileScreen").d("Skipping resync for user $userId - already resynced this session")
    }
}
```

## Design Rationale

### Why Once Per Session?

1. **Performance**: `resync_user` is a long-running backend operation that aggregates data across distributed nodes
2. **Cost**: Reduces unnecessary server load and network calls
3. **User Experience**: Prevents multiple expensive operations while navigating profiles
4. **Data Freshness**: Session-based is sufficient since user data doesn't change that frequently

### Session Lifetime

- **Starts**: When app launches
- **Ends**: When app process terminates
- **Persists**: Across navigation, screen rotation, and app backgrounding
- **Resets**: Only on app restart

### Thread Safety

Both iOS and Android implementations use locks to ensure thread-safe access to the session set:

- **iOS**: `NSLock().withLock`
- **Android**: `synchronized(resyncLock)`

This prevents race conditions when multiple coroutines/tasks check and update the set simultaneously.

## API Specification

### Endpoint
```
resync_user
```

### Parameters
```kotlin
{
    "aid": appId,           // Application ID
    "ver": "last",          // Version
    "version": "v2",        // API version
    "userid": userId        // User ID to resync
}
```

### Response (v2 format)
```kotlin
{
    "name": String?,
    "username": String?,
    "email": String?,
    "profile": String?,
    "avatar": String?,
    "tweetCount": Int?,
    "followingCount": Int?,
    "followersCount": Int?,
    "bookmarksCount": Int?,
    "favoritesCount": Int?,
    "commentsCount": Int?,
    "cloudDrivePort": Int?
}
```

## Usage Flow

```
User opens profile screen
  └─> ProfileScreen.LaunchedEffect(userId)
       └─> Check: HproseInstance.shouldResyncUser(userId)?
            ├─> Yes (first time this session)
            │    └─> withContext(IO) {
            │         └─> HproseInstance.resyncUser(userId)
            │              └─> Call backend: resync_user API
            │                   └─> Update user properties
            │                        └─> TweetCacheManager.saveUser(user)
            │                             └─> Log: "✅ Successfully resynced"
            │         }
            └─> No (already resynced)
                 └─> Log: "Skipping resync - already done this session"
```

## Testing Considerations

### Scenarios to Test

1. **First profile open** - Should trigger resync
2. **Same profile reopened** - Should skip resync
3. **Different profiles** - Each should resync once
4. **App restart** - Session resets, all profiles resync again
5. **Concurrent access** - Thread safety with multiple rapid profile opens

### Debug Logs

```kotlin
// When checking
Timber.tag("ProfileScreen").d("Checking resync for user: $userId")

// When resyncing
Timber.tag("resyncUser").d("Starting resync for user: $userId")
Timber.tag("resyncUser").d("✅ Successfully resynced user $userId")

// When skipping
Timber.tag("ProfileScreen").d("Skipping resync - already done this session")

// On failure
Timber.tag("resyncUser").e(e, "Failed to resync user $userId")
```

## Comparison: Android vs iOS

| Aspect | iOS | Android | Match? |
|--------|-----|---------|--------|
| Session tracking | `Set<String>` | `MutableSet<MimeiId>` | ✅ |
| Thread safety | `NSLock` | `synchronized` | ✅ |
| API endpoint | `resync_user` v2 | `resync_user` v2 | ✅ |
| Execution | `Task.detached` | `withContext(IO)` | ✅ |
| Trigger | Profile open | Profile open | ✅ |
| Frequency | Once per session | Once per session | ✅ |
| Cache update | `TweetCacheManager.saveUser` | `TweetCacheManager.saveUser` | ✅ |

## Benefits

1. **Consistency**: Ensures user data is refreshed across distributed nodes
2. **Efficiency**: Avoids redundant expensive operations
3. **Platform parity**: Android now matches iOS behavior exactly
4. **Data integrity**: Server-side aggregation ensures accurate counts and metadata
5. **User experience**: Background operation doesn't block UI

## Related Files

### Android
- `app/src/main/java/us/fireshare/tweet/HproseInstance.kt` - API implementation
- `app/src/main/java/us/fireshare/tweet/profile/ProfileScreen.kt` - UI integration

### iOS
- `Sources/Core/HproseInstance.swift` - API implementation
- `Sources/Features/Profile/ProfileView.swift` - UI integration

## Migration Notes

### Before (Android)
- No `resync_user` call
- User data only refreshed via `get_user` (client-side fetch)

### After (Android)
- `resync_user` called once per session per user
- Matches iOS behavior
- Ensures server-side data consistency

---

**Last Updated**: 2026-01-01  
**Implemented In**: Android v41+, iOS v41+

