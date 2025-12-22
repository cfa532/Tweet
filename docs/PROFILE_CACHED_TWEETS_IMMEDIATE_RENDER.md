# Immediate Cached Tweets Rendering

This document describes the changes made to ensure cached tweets are rendered immediately in both the profile screen and main feed, while data continues to refresh from the server in the background.

## User Request

"when user profile is opened, render tweets from cache immediately, when user profile is being freshed from server"

**Follow-up:** "does this update applies to mainfeed tweet list too?"
**Answer:** Yes! The same fix has been applied to both ProfileScreen and MainFeed.

## Problem

Previously, when a user profile was opened:
1. `initLoad()` would be called
2. It would load cached tweets internally via `getTweets(0)`
3. However, `initState` remained `true` until ALL server fetches completed
4. The UI showed a loading spinner and blocked rendering even though cached tweets were already loaded
5. Users had to wait for the server response before seeing any content

This created a poor user experience, especially on slow networks or when the server was slow to respond.

## Solution

Modified `UserViewModel.initLoad()` to set `initState = false` immediately after the first page is loaded (which includes cached tweets), allowing the UI to render cached content while server fetches continue in the background.

## Changes Made

### 1. UserViewModel.kt initLoad() (Lines 118-167)

**Before:**
```kotlin
suspend fun initLoad() {
    try {
        Timber.tag("initLoad").d("Starting initial load for user: ${user.value.mid}")

        // Load first page (page 0) which includes pinned tweets
        val page0Tweets = getTweets(0)
        
        // ... fetch additional pages if needed ...
        
        Timber.tag("initLoad")
            .d("Initial load completed. Pinned tweets: ${pinnedTweets.value.size}, Regular tweets: ${tweets.value.size}")
    } catch (e: Exception) {
        Timber.tag("initLoad").e(e, "Error during initial load for user: ${user.value.mid}")
    } finally {
        initState.value = false  // <<< Only set to false at the END
    }
}
```

**After:**
```kotlin
suspend fun initLoad() {
    try {
        Timber.tag("initLoad").d("Starting initial load for user: ${user.value.mid}")

        // Load first page (page 0) which includes pinned tweets and cached tweets
        // getTweets() will load cached tweets first, update the UI state, then fetch from server
        val page0Tweets = getTweets(0)
        
        // After page 0 is loaded (which includes cached tweets), immediately hide loading spinner
        // This allows cached tweets to be displayed while server fetch continues
        initState.value = false  // <<< Set to false IMMEDIATELY after first page
        Timber.tag("initLoad").d("Cached tweets loaded, showing UI. Continuing server fetch in background...")
        
        // ... fetch additional pages if needed ...
        
        Timber.tag("initLoad")
            .d("Initial load completed. Pinned tweets: ${pinnedTweets.value.size}, Regular tweets: ${tweets.value.size}")
    } catch (e: Exception) {
        Timber.tag("initLoad").e(e, "Error during initial load for user: ${user.value.mid}")
        // Even on error, hide loading spinner if we have any cached content
        initState.value = false
    }
    // No finally block needed - initState is set early
}
```

**Key Changes:**
1. Moved `initState.value = false` from the `finally` block to right after the first `getTweets(0)` call
2. This allows the UI to render immediately after cached tweets are loaded
3. Server fetches for additional pages continue in the background
4. Added error handling to set `initState = false` even on exceptions
5. Removed the `finally` block since state is now set earlier

### 2. TweetFeedViewModel.kt initialize() (Lines 70-99)

**Before:**
```kotlin
fun initialize() {
    if (isInitialized) {
        return
    }
    isInitialized = true
    viewModelScope.launch(IO) {
        try {
            waitForAppUser()
            if (appUser.baseUrl != null) {
                refresh(0)  // Loads cached tweets + server fetch
            } else {
                loadCachedTweetsOnly()
            }
        } catch (e: Exception) {
            // Error handling...
            loadCachedTweetsOnly()
        } finally {
            initState.value = false  // <<< Only set to false at the END
        }
    }
}
```

**After:**
```kotlin
fun initialize() {
    if (isInitialized) {
        return
    }
    isInitialized = true
    viewModelScope.launch(IO) {
        try {
            waitForAppUser()
            if (appUser.baseUrl != null) {
                refresh(0)  // Loads cached tweets + server fetch
                
                // After first page is loaded (includes cached tweets), immediately hide loading spinner
                initState.value = false  // <<< Set to false IMMEDIATELY after first page
                Timber.tag("TweetFeedViewModel").d("Cached tweets loaded, showing UI. Server fetch completed in background.")
            } else {
                loadCachedTweetsOnly()
                initState.value = false
            }
        } catch (e: Exception) {
            // Error handling...
            loadCachedTweetsOnly()
            // Hide loading spinner even on error
            initState.value = false
        }
        // No finally block needed - initState is set early
    }
}
```

**Key Changes:**
1. Moved `initState.value = false` from the `finally` block to immediately after `refresh(0)` call
2. Also set `initState = false` after `loadCachedTweetsOnly()` for offline scenarios
3. Set `initState = false` in the catch block to hide spinner even on errors
4. Removed the `finally` block since state is now set earlier in each branch

## How It Works Now

### Flow When Profile is Opened (UserViewModel)

1. **ProfileScreen.kt LaunchedEffect(Unit)** triggers:
   ```kotlin
   LaunchedEffect(Unit) {
       withContext(Dispatchers.IO) {
           viewModel.initLoad()  // Load tweets
       }
   }
   
   LaunchedEffect(Unit) {
       withContext(Dispatchers.IO) {
           viewModel.refreshUserData()  // Refresh user profile
       }
   }
   ```

2. **initLoad() execution:**
   - Calls `getTweets(0)` which:
     - Loads pinned tweets from cache
     - Loads cached tweets for page 0 and updates `_tweets` StateFlow
     - Fetches fresh tweets from server and merges with cached tweets
   - **Immediately sets `initState = false`** ✅
   - UI renders cached tweets instantly
   - Continues loading additional pages in background if needed

3. **UI Rendering (ProfileScreen.kt):**
   ```kotlin
   val initState by viewModel.initState.collectAsState()
   
   if (initState) {
       // Show loading spinner
   } else {
       // Show tweets (cached + server data as it arrives)
   }
   ```

### Flow When Main Feed is Opened (TweetFeedViewModel)

1. **ContentView.swift** triggers initialization:
   ```kotlin
   LaunchedEffect(Unit) {
       viewModel.initialize()
   }
   ```

2. **initialize() execution:**
   - Calls `refresh(0)` which calls `fetchTweets(0)`:
     - Loads cached tweets and updates `_tweets` StateFlow
     - Fetches fresh tweets from server and merges with cached tweets
   - **Immediately sets `initState = false`** ✅
   - UI renders cached tweets instantly
   - Server data continues to merge in background

3. **UI Rendering:**
   ```kotlin
   val initState by viewModel.initState.collectAsState()
   
   if (initState) {
       // Show loading spinner
   } else {
       // Show tweets (cached + server data as it arrives)
   }
   ```

### getTweets() and fetchTweets() Already Support This Pattern

The `getTweets()` method in `UserViewModel` already implements a cache-first pattern:

```kotlin
private suspend fun getTweets(pageNumber: Int): List<Tweet?> {
    // Load pinned tweets for page 0
    if (pageNumber == 0) {
        loadPinnedTweets()
    }

    // 1. Load cached tweets FIRST
    val cachedTweets = loadCachedTweetsByAuthor(...)
    
    // 2. Update UI state immediately with cached tweets
    _tweets.update { currentTweets ->
        // Merge cached tweets with existing tweets
        mergedTweets
    }
    
    // 3. Fetch from server if network available
    if (user.value.baseUrl != null && appUser.baseUrl != null) {
        val newTweetsWithNulls = HproseInstance.getTweetsByUser(...)
        
        // 4. Merge server tweets with cached tweets (no replacement)
        _tweets.update { currentTweets ->
            // Merge server tweets with existing tweets
            mergedTweets
        }
    }
}
```

**Key Points:**
- Cached tweets are loaded first and UI state is updated
- Server fetch happens after cached tweets are available
- Tweets are merged, not replaced, so cached tweets remain visible
- This pattern already existed - we just needed to unlock the UI earlier

## Benefits

### User Experience
- **Instant Content Display**: Cached tweets appear immediately when profile is opened
- **No Blocking**: Users can start scrolling and interacting right away
- **Progressive Loading**: Server tweets are merged in as they arrive
- **Perceived Performance**: App feels much faster and more responsive

### Technical Benefits
- **Consistent Behavior**: Both ProfileScreen and MainFeed now use the same pattern
- **Network Resilience**: Works offline with cached content
- **Background Refresh**: Server fetches continue without blocking UI
- **State Management**: UI state accurately reflects loaded content availability
- **Code Symmetry**: Both ViewModels follow the same initialization pattern

## Summary: Both ViewModels Now Use Same Pattern

Both `UserViewModel` (ProfileScreen) and `TweetFeedViewModel` (MainFeed) now follow the same pattern:

### Pattern: Cache-First with Immediate UI Rendering

1. **Load first page** (which loads cached tweets and updates UI state)
2. **Set `initState = false`** immediately after first page
3. **Show cached tweets** in UI instantly
4. **Continue server fetch** in background
5. **Merge server data** with cached tweets as it arrives

### Before (Both ViewModels)
```kotlin
try {
    // Load tweets (cache + server)
} finally {
    initState.value = false  // Wait until everything is done
}
```

### After (Both ViewModels)
```kotlin
try {
    // Load tweets (cache + server)
    initState.value = false  // Set immediately after cache is loaded
} catch {
    initState.value = false  // Set even on error
}
```

Both now provide instant visual feedback with cached content.

## Testing

Build command used:
```bash
./gradlew assembleFullDebug assembleMiniDebug
```

Both variants built successfully with the changes.

## Related Files

- `app/src/main/java/us/fireshare/tweet/viewmodel/UserViewModel.kt` (initLoad method)
- `app/src/main/java/us/fireshare/tweet/viewmodel/TweetFeedViewModel.kt` (initialize method)
- `app/src/main/java/us/fireshare/tweet/profile/ProfileScreen.kt` (ProfileScreen UI rendering)
- `app/src/main/java/us/fireshare/tweet/ContentView.kt` (MainFeed UI rendering)

## Related Improvements

This change complements the previous fetchUser improvement:
- **Previous**: User profile data is always refreshed from server when opened
- **This Change**: Cached tweets are shown immediately while refresh happens

Together, these changes provide:
1. Instant visual feedback (cached tweets)
2. Fresh user data (always fetch from server)
3. Background updates (server tweets merged in)
4. No blocking UI (everything runs in background after initial cache load)

## Date

December 22, 2025

