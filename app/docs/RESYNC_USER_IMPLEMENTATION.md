# Resync User - Implementation Summary

## Quick Reference

**What**: Backend API call to refresh user data on server side  
**When**: Once per app session when user profile is opened  
**Why**: Ensures data consistency across distributed nodes  
**Matches**: iOS ProfileView behavior

## Key Components

### 1. Session Tracking
```kotlin
// HproseInstance.kt
private val resyncedUsersThisSession = mutableSetOf<MimeiId>()
private val resyncLock = Any()

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
```

### 2. API Call
```kotlin
// HproseInstance.kt
suspend fun resyncUser(userId: MimeiId): User? {
    // Calls "resync_user" v2 API
    // Updates user properties from server
    // Saves to cache
}
```

### 3. UI Integration
```kotlin
// ProfileScreen.kt
LaunchedEffect(userId) {
    if (HproseInstance.shouldResyncUser(userId)) {
        withContext(Dispatchers.IO) {
            val user = HproseInstance.resyncUser(userId)
            if (user != null) {
                TweetCacheManager.saveUser(user)
            }
        }
    }
}
```

## Behavior

### First Profile Open (Session Start)
```
User opens profile → shouldResync = true → Call API → Update cache
```

### Same Profile Reopened
```
User opens same profile → shouldResync = false → Skip (already synced)
```

### Different Profile
```
User opens different profile → shouldResync = true → Call API → Update cache
```

### After App Restart
```
Session resets → All profiles will resync on first open
```

## Testing

### Expected Logs

**First open:**
```
ProfileScreen: Checking resync for user: abc123
resyncUser: Starting resync for user: abc123
resyncUser: Calling resync_user API with baseUrl: http://...
resyncUser: ✅ Successfully resynced user abc123
ProfileScreen: ✅ Successfully resynced user abc123 on server
```

**Subsequent opens:**
```
ProfileScreen: Skipping resync for user abc123 - already resynced this session
```

## API Details

**Endpoint:** `resync_user`  
**Version:** v2  
**Method:** `runMApp`  

**Parameters:**
```kotlin
{
    "aid": appId,
    "ver": "last",
    "version": "v2",
    "userid": userId
}
```

**Response:** User object with updated properties

## Thread Safety

✅ **Thread-safe** using `synchronized(resyncLock)`  
✅ **Prevents race conditions** when multiple coroutines access simultaneously  
✅ **Atomic check-and-set** operation

## Benefits

1. ✅ **Matches iOS** - Platform parity achieved
2. ✅ **Efficient** - Once per session prevents redundant calls
3. ✅ **Non-blocking** - Runs on IO dispatcher
4. ✅ **Data consistency** - Server-side aggregation
5. ✅ **User experience** - Happens in background

## Files Modified

1. `HproseInstance.kt` - Added `resyncUser()` and session tracking
2. `ProfileScreen.kt` - Calls resync on profile open
3. `docs/RESYNC_USER_SESSION_DESIGN.md` - Full design document

## Related

- See `docs/RESYNC_USER_SESSION_DESIGN.md` for detailed design
- iOS reference: `Sources/Features/Profile/ProfileView.swift`
- Backend: `resync_user` v2 API endpoint

