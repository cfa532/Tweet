# Fix: Tweet Author Being Overwritten by Null Object

**Date:** January 3, 2026  
**Issue:** Tweet authors were being overwritten with null objects in the main feed  
**Status:** Fixed

## Problem Description

When the app starts and loads the main feed, cached tweets and users are displayed. However, sometimes the author of a tweet would be overwritten by an invalid null object, causing the author information to disappear.

## Root Cause

The issue occurred in multiple locations in `HproseInstance.kt` where `fetchUser()` was called to populate tweet authors:

```kotlin
val tweet = Tweet.from(tweetJson)
tweet.author = fetchUser(tweet.authorId)  // ⚠️ Can return null!
updateCachedTweet(tweet, userId = appUser.mid)
```

### Why This Causes Problems

1. **Network Failures**: `fetchUser()` can return `null` if:
   - Network request fails or times out
   - User is blacklisted
   - Server error occurs
   - User data is incomplete/invalid

2. **Author Overwritten**: When `fetchUser()` returns `null`, the tweet's author field is set to `null`, overwriting any existing author data.

3. **Cached Without Author**: The tweet is then saved to cache with `author = null` (even though the author field is transient and not serialized to database).

4. **Loaded Back as Null**: When loading from cache later:
   ```kotlin
   tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
   ```
   If the user cache also doesn't have that author, the tweet.author remains `null`.

## Solution

Modified all locations where `fetchUser()` is used to populate tweet authors to use a **fallback strategy**:

```kotlin
// Fetch author, fallback to cached user if fetchUser fails
val fetchedAuthor = fetchUser(tweet.authorId)
tweet.author = fetchedAuthor ?: TweetCacheManager.getCachedUser(tweet.authorId)

// Log warning if author is still null
if (tweet.author == null) {
    Timber.tag("getTweetFeed").w("⚠️ Failed to get author for tweet ${tweet.mid}, authorId: ${tweet.authorId}")
}
```

### For Toggle Operations (favorite/bookmark)

In operations where we already have a tweet with an author, we preserve the existing author:

```kotlin
// Preserve author from original tweet, or fetch if not available
val fetchedAuthor = fetchUser(updatedTweet.authorId)
updatedTweet.author = fetchedAuthor ?: tweet.author ?: TweetCacheManager.getCachedUser(updatedTweet.authorId)
```

## Locations Fixed

### 1. `getTweetFeed()` - Main Feed Tweets (Line 1642-1666)
- **Original tweets**: Added fallback to cached user
- **Main feed tweets**: Added fallback to cached user
- **Impact**: Prevents null authors when loading main feed on app start

### 2. `getTweetsByUser()` - User Profile Tweets (Line 1764-1793)
- **Original tweets**: Added fallback to cached user
- **Impact**: Prevents null authors when viewing user profiles

### 3. `toggleFavorite()` - Like/Unlike Operations (Line 2410-2433)
- **Updated tweet**: Preserves original tweet's author, then falls back to cache
- **Impact**: Prevents losing author when toggling favorites

### 4. `toggleBookmark()` - Bookmark Operations (Line 2471-2494)
- **Updated tweet**: Preserves original tweet's author, then falls back to cache
- **Impact**: Prevents losing author when toggling bookmarks

### 5. `getUserTweetsByType()` - Favorites/Bookmarks/Comments (Line 2540-2554)
- **Loaded tweets**: Added fallback to cached user
- **Impact**: Prevents null authors when viewing user's favorites, bookmarks, or comments

## Benefits

1. **Resilience**: Tweet authors are preserved even when network requests fail
2. **User Experience**: Users continue to see author information even in poor network conditions
3. **Cache Integrity**: Tweets are cached with valid author data whenever possible
4. **Debugging**: Warning logs help identify when author fetching fails

## Testing Recommendations

1. **Network Failure**: Test with airplane mode or poor network conditions
2. **Cold Start**: Clear app cache and restart the app
3. **User Profiles**: Navigate to user profiles and verify authors are shown
4. **Interactions**: Toggle favorites/bookmarks and verify authors remain visible
5. **Edge Cases**: Test with users who have incomplete data or are blacklisted

## Related Files

- `HproseInstance.kt` - Main fix implementation
- `TweetCacheManager.kt` - User cache fallback
- `Tweet.kt` - Tweet data model (author field)
- `TWEET_CACHING_ALGORITHM.md` - Caching strategy documentation


