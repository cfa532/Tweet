# Tweet Caching Algorithm

**Date:** January 2025  
**Status:** Matches iOS behavior  
**Purpose:** Document the tweet caching strategy for Android app

## Overview

The Android app now uses the same tweet caching algorithm as the iOS version. Tweets are cached by their author's mid (member ID), except for mainfeed tweets which are cached by appUser's mid. The cache persists across login/logout and is cleared periodically or manually by the user.

## Caching Strategy

### Core Principle

**Most tweets are cached by `authorId` (tweet.authorId), except mainfeed tweets which are cached by `appUser.mid`.**

This ensures:
- Original tweets are cached under their author's cache, not the current user's
- Tweets from different authors are properly segregated
- Mainfeed tweets persist in the user's personal cache for quick access
- Cache can be efficiently queried by author

## Caching Rules by Context

### 1. Mainfeed Tweets (`getTweetFeed`)

**Location:** `HproseInstance.getTweetFeed()`

**Behavior:**
- **Original tweets:** Cached by `originalTweet.authorId`
- **Mainfeed tweets:** Cached by `appUser.mid` (exception to the rule)

**Reasoning:**
- Mainfeed contains tweets from multiple authors
- Caching by `appUser.mid` allows all mainfeed tweets to persist together
- Original tweets are cached by their author for consistency with other contexts
- Matches iOS behavior exactly

**Implementation:**
```kotlin
// Original tweets - cache by authorId
originalTweetsData?.forEach { originalTweetJson ->
    val originalTweet = Tweet.from(originalTweetJson)
    originalTweet.author = getUser(originalTweet.authorId)
    TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId, shouldCache = true)
}

// Mainfeed tweets - cache by appUser.mid (exception)
tweetsData?.map { tweetJson ->
    val tweet = Tweet.from(tweetJson)
    tweet.author = getUser(tweet.authorId)
    // Mainfeed tweets are cached by appUser.mid (exception to the rule)
    updateCachedTweet(tweet, userId = appUser.mid)
    tweet
}
```

### 2. User Profile Tweets (`getTweetsByUser`)

**Location:** `HproseInstance.getTweetsByUser()`

**Behavior:**
- **Original tweets:** Cached by `originalTweet.authorId`
- **User tweets:** Only cached if `user.mid == appUser.mid`, then cached by `appUser.mid`

**Reasoning:**
- Only the current user's profile tweets are cached to persist across sessions
- Other users' profile tweets are not cached (memory cache only)
- Original tweets are always cached by their author for consistency

**Implementation:**
```kotlin
// Original tweets - cache by authorId
originalTweetsData?.forEach { originalTweetJson ->
    val originalTweet = Tweet.from(originalTweetJson)
    originalTweet.author = getUser(originalTweet.authorId)
    TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId, shouldCache = false)
}

// User tweets - only cache if it's appUser's profile
val result = tweetsData?.map { tweetJson ->
    val tweet = Tweet.from(tweetJson)
    tweet.author = user
    // Only cache tweets if it's the appUser's profile
    if (user.mid == appUser.mid) {
        updateCachedTweet(tweet, userId = appUser.mid, shouldCache = false)
    }
    tweet
}
```

### 3. Individual Tweet Fetch (`fetchTweet`)

**Location:** `HproseInstance.fetchTweet()`

**Behavior:**
- Cached by `authorId` (tweet's author, not appUser)

**Reasoning:**
- Individual tweets are fetched for a specific author
- Caching by `authorId` ensures consistency with other contexts
- The tweet can be found regardless of who fetches it

**Implementation:**
```kotlin
Tweet.from(tweetData).apply {
    this.author = author
    // Cache tweet by authorId, not appUser.mid
    TweetCacheManager.saveTweet(this, userId = authorId, shouldCache = shouldCache)
}
```

### 4. Tweet Updates

#### Favorite/Bookmark (`toggleFavorite`, `toggleBookmark`)

**Behavior:**
- Updated tweet cached by `updatedTweet.authorId`

**Implementation:**
```kotlin
val updatedTweet = Tweet.from(updatedTweetData)
updatedTweet.author = getUser(updatedTweet.authorId)
// Cache by authorId
updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
```

#### Retweet (`retweet`)

**Behavior:**
- **Retweet itself:** Cached by `appUser.mid`
- **Updated original tweet:** Cached by `updatedTweet.authorId`

**Reasoning:**
- Retweets are created by the current user, so cached in their personal cache
- Original tweet's retweet count is updated, cached under original author

**Implementation:**
```kotlin
// Update retweet count and cache original tweet
updateRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
    // Cache updated original tweet by authorId
    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
}

// Cache the retweet by appUser.mid
updateCachedTweet(retweet, userId = appUser.mid)
```

#### Comment Upload (`uploadComment`)

**Behavior:**
- Updated parent tweet cached by `updatedTweet.authorId`

**Implementation:**
```kotlin
val updatedTweet = tweet.copy(
    commentCount = (response["count"] as? Number)?.toInt() ?: tweet.commentCount
)
// Cache by authorId
updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
```

#### Retweet Deletion (`deleteTweet`)

**Behavior:**
- Updated original tweet cached by `updatedTweet.authorId`

**Implementation:**
```kotlin
HproseInstance.updateRetweetCount(originalTweet, tweet.mid, -1)?.let { updatedTweet ->
    // Cache updated original tweet by authorId
    HproseInstance.updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
}
```

### 5. New Uploaded Tweets (`uploadTweet`)

**Behavior:**
- New tweets posted by appUser cached by `appUser.mid`

**Reasoning:**
- New tweets are always from the current user
- Cached in their personal cache for persistence

**Implementation:**
```kotlin
// In TweetFeedViewModel and UserViewModel
is TweetEvent.TweetUploaded -> {
    val tweetWithAuthor = event.tweet
    if (tweetWithAuthor.authorId == appUser.mid) {
        // Cache the new tweet by appUser.mid (matches iOS behavior)
        TweetCacheManager.saveTweet(tweetWithAuthor, appUser.mid, shouldCache = true)
    }
}
```

## Cache Persistence and Expiration

### Login/Logout Behavior

**Cache is NOT cleared on login/logout.**

**Reasoning:**
- Cache persists per user and is cleared periodically or manually
- Allows offline access to cached tweets
- Improves performance by avoiding re-downloads

**Implementation:**
```kotlin
suspend fun logout(popBack: () -> Unit) {
    // ... reset user state ...
    // Note: Tweet cache is NOT cleared on logout to match iOS behavior
    // Cache persists per user and is cleared periodically or manually by user
}
```

### Cache Expiration

**Important:** Even though we search across all caches when looking up tweets, cached tweets are still expired by:
1. **Timestamp:** Tweets older than 30 days are automatically expired
2. **Manual Clear:** User can manually clear cache via settings screen

**Expiration Rules:**
- All cached tweets (regardless of which `uid` they're stored under) are subject to expiration
- Expiration is based on the `timestamp` field in `CachedTweet`
- Expiration applies to both memory cache and database cache
- Expired tweets are cleaned up by the `CleanUpWorker` periodically

**Implementation:**
```kotlin
// Cache expiration time (30 days)
private const val CACHE_EXPIRATION_TIME = 30 * 24 * 60 * 60 * 1000L

fun cleanupExpiredTweets() {
    val cutoffDate = Date(System.currentTimeMillis() - CACHE_EXPIRATION_TIME)
    
    // Remove expired tweets from database (searches across ALL caches)
    dao.deleteOldCachedTweets(cutoffDate)  // WHERE timestamp < cutoffDate
    
    // Remove expired tweets from memory cache
    val expiredKeys = memoryCache.filter { (_, cachedTweet) ->
        isExpired(cachedTweet)
    }.keys
    
    expiredKeys.forEach { key ->
        memoryCache.remove(key)
    }
}
```

**Database Query:**
```sql
-- Deletes expired tweets across ALL caches
DELETE FROM CachedTweet WHERE timestamp < :oneMonthAgo
-- Note: No WHERE uid = ... clause - expiration applies to all caches
```

### Cache Cleanup Methods

Cache is cleared:
- **Periodically:** By the cleanup worker (tweets older than 30 days)
- **Manually:** Via settings screen (user-initiated clear all)
- **Never:** On login/logout (matches iOS behavior)

**Important:** When clearing expired tweets, the cleanup worker searches across ALL caches (by timestamp only, not filtered by `uid`). This ensures all expired tweets are removed regardless of which cache they're stored in.

## Database Schema

### CachedTweet Entity

```kotlin
@Entity(indices = [Index(value = ["uid"])])
data class CachedTweet(
    @PrimaryKey val mid: MimeiId,      // Tweet's mimei Id (unique key)
    val uid: MimeiId,                  // Cache key: authorId or appUser.mid
    val originalTweet: Tweet,          // Full tweet object
    val timestamp: Date = Date()       // Cache timestamp
)
```

**Key Fields:**
- `mid`: Primary key (tweet ID)
- `uid`: Cache key - either `authorId` or `appUser.mid` depending on context
- `originalTweet`: Complete tweet data stored as JSON

## Cache Queries

### By Tweet ID

**IMPORTANT:** When looking up cached tweets by tweet ID (`mid`), we search across **ALL** user caches, not just a specific user's cache.

**Reasoning:**
- Original tweets are cached under their `authorId`
- Mainfeed tweets are cached under `appUser.mid`
- Retweets are cached under `appUser.mid`
- When we only have a tweet `mid`, we don't know which cache (`uid`) it's stored in

**Implementation:**
```kotlin
/**
 * IMPORTANT: Searches across ALL user caches, not just a specific user's cache.
 * This is necessary because tweets can be cached under different uid values.
 * The search is performed by tweet mid (primary key) only, not filtered by uid.
 */
fun getCachedTweet(tweetId: MimeiId): Tweet? {
    // Check memory cache first (searches across all tweets by mid)
    memoryCache[tweetId]?.let { cachedTweet ->
        return cachedTweet.originalTweet
    }
    
    // Check database cache (searches across all caches by mid, not filtered by uid)
    val dbCachedTweet = dao.getCachedTweet(tweetId)  // WHERE mid = :tweetId (no uid filter)
    dbCachedTweet?.let { cachedTweet ->
        memoryCache[tweetId] = cachedTweet
        return cachedTweet.originalTweet
    }
    
    return null
}
```

**Database Query:**
```sql
SELECT * FROM CachedTweet WHERE mid = :tweetId
-- Note: No WHERE uid = ... clause - searches across all caches
-- Expired tweets are still removed by timestamp regardless of which cache they're in
```

### By User ID

```kotlin
fun fetchCachedTweets(for userId: String, page: Int, pageSize: Int): List<Tweet?> {
    // Fetches tweets cached under specific userId
    // Used for loading mainfeed (appUser.mid) or user profiles (authorId)
}
```

## Benefits

1. **Consistent Behavior:** Matches iOS caching exactly
2. **Efficient Storage:** Tweets cached by author allows better organization
3. **Persistence:** Cache survives login/logout for better offline access
4. **Author-Based Queries:** Can efficiently query tweets by author
5. **Mainfeed Exception:** Mainfeed tweets cached together for quick access

## Comparison: Before vs After

### Before (Old Algorithm)

- All tweets cached by `appUser.mid`
- Cache cleared on logout
- Inconsistent with iOS behavior

### After (New Algorithm)

- Most tweets cached by `authorId`
- Mainfeed tweets cached by `appUser.mid`
- Cache persists across login/logout
- Matches iOS behavior exactly

## Testing Recommendations

1. **Test Mainfeed Caching:**
   - Load mainfeed, verify tweets cached by `appUser.mid`
   - Verify original tweets cached by `authorId`
   - Check cache after logout (should persist)

2. **Test Profile Caching:**
   - View own profile, verify tweets cached by `appUser.mid`
   - View other user's profile, verify tweets not cached (memory only)
   - Verify original tweets always cached by `authorId`

3. **Test Tweet Updates:**
   - Favorite/bookmark a tweet, verify cached by `authorId`
   - Retweet a tweet, verify retweet cached by `appUser.mid`, original by `authorId`
   - Comment on tweet, verify parent cached by `authorId`

4. **Test Cache Persistence:**
   - Logout and login, verify cache persists
   - Check cached tweets are still available offline
   - Verify manual cache clear works correctly

## Files Changed

1. **`HproseInstance.kt`**
   - `getTweetFeed()`: Original tweets by `authorId`, mainfeed by `appUser.mid`
   - `getTweetsByUser()`: Only cache if `user.mid == appUser.mid`
   - `fetchTweet()`: Cache by `authorId`
   - `updateCachedTweet()`: Accept optional `userId` parameter
   - `toggleFavorite()`, `toggleBookmark()`: Cache by `authorId`
   - `retweet()`: Retweet by `appUser.mid`, original by `authorId`
   - `uploadComment()`: Cache by `authorId`

2. **`TweetFeedViewModel.kt`**
   - `TweetUploaded` event: Cache new tweets by `appUser.mid`

3. **`UserViewModel.kt`**
   - `logout()`: Removed cache clearing
   - `TweetUploaded` event: Cache new tweets by `appUser.mid`
   - `reset()`: Removed cache clearing

4. **`TweetDropdownMenuItems.kt`**
   - Retweet deletion: Cache updated original by `authorId`

## References

- iOS Implementation: `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift`
- iOS Cache Manager: `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/TweetCacheManager.swift`
- Android Cache Manager: `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

