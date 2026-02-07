# Tweet Caching Algorithm

**Date:** January 2025  
**Status:** Updated - All tweets cached, mainfeed by appUser.mid, others by authorId  
**Purpose:** Document the tweet caching strategy for Android app

## Overview

The Android app uses a consistent tweet caching algorithm. Tweets are cached by their author's mid (member ID), except for mainfeed tweets which are cached by appUser's mid. The cache persists across login/logout and is cleared periodically or manually by the user.

## Caching Strategy

### Core Principle

**Mainfeed tweets are cached by `appUser.mid`. All other tweets are cached by their `authorId`.**

This ensures:
- Mainfeed tweets are grouped together under the user's cache for quick access
- User profile tweets are cached by their author for consistency
- Individual tweets are cached by their author
- Cache can be efficiently queried by context

## Caching Rules by Context

### 1. Mainfeed Tweets (`getTweetFeed`)

**Location:** `HproseInstance.getTweetFeed()`

**Behavior:**
- **Original tweets:** Cached by `originalTweet.authorId`
- **Mainfeed tweets:** Cached by `appUser.mid`

**Reasoning:**
- Mainfeed contains tweets from multiple authors
- Caching by `appUser.mid` allows all mainfeed tweets to persist together
- Original tweets are cached by their author for consistency with other contexts

**Implementation:**
```kotlin
// Original tweets - cache by authorId
originalTweetsData?.forEach { originalTweetJson ->
    val originalTweet = Tweet.from(originalTweetJson)
    originalTweet.author = getUser(originalTweet.authorId)
    TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
}

// Mainfeed tweets - cache by appUser.mid
tweetsData?.map { tweetJson ->
    val tweet = Tweet.from(tweetJson)
    tweet.author = getUser(tweet.authorId)
    // Mainfeed tweets are cached by appUser.mid
    updateCachedTweet(tweet, userId = appUser.mid)
    tweet
}
```

**Loading:**
```kotlin
// Load cached tweets for mainfeed from appUser.mid
dao.getCachedTweetsByUser(appUser.mid, startRank, count)
```

### 2. User Profile Tweets (`getTweetsByUser`)

**Location:** `HproseInstance.getTweetsByUser()`

**Behavior:**
- **Original tweets:** Cached by `originalTweet.authorId`
- **User tweets:** Cached by `tweet.authorId` (the profile user's ID)

**Reasoning:**
- User profile tweets are cached by their author for consistency
- All tweets are cached to database for offline access
- Original tweets are always cached by their author

**Implementation:**
```kotlin
// Original tweets - cache by authorId
originalTweetsData?.forEach { originalTweetJson ->
    val originalTweet = Tweet.from(originalTweetJson)
    originalTweet.author = getUser(originalTweet.authorId)
    TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
}

// User tweets - cache by authorId
val result = tweetsData?.map { tweetJson ->
    val tweet = Tweet.from(tweetJson)
    tweet.author = user
    // Cache all tweets by their authorId
    updateCachedTweet(tweet, userId = tweet.authorId)
    tweet
}
```

**Loading:**
```kotlin
// Load cached tweets for user profile from authorId (userId)
dao.getCachedTweetsByUser(userId, startRank, count)
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
    // Cache tweet by authorId
    TweetCacheManager.saveTweet(this, userId = authorId)
}
```

### 4. New Tweet Posting

**Location:** `TweetFeedViewModel.uploadTweet()`, `UserViewModel.uploadTweet()`

**Behavior:**
- **Mainfeed:** Cached by `appUser.mid` (when posted from mainfeed)
- **User profile:** Cached by `authorId` (when posted from profile)

**Implementation:**
```kotlin
// Mainfeed - cache under appUser.mid
TweetCacheManager.saveTweet(tweetWithAuthor, appUser.mid)

// User profile - cache under authorId
TweetCacheManager.saveTweet(tweetWithAuthor, tweetWithAuthor.authorId)
```

### 5. Tweet Updates

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

#### Retweet (`toggleRetweet`)

**Behavior:**
- **Retweet itself:** Cached by `retweet.authorId` (the user who retweeted)
- **Updated original tweet:** Cached by `updatedTweet.authorId`

**Reasoning:**
- Retweets are cached by their author (the user who created the retweet)
- Original tweet's retweet count is updated, cached under original author

**Implementation:**
```kotlin
// Update retweet count and cache original tweet
updateRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
    // Cache updated original tweet by authorId
    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
}

// Cache the retweet by its authorId
updateCachedTweet(retweet, userId = retweet.authorId)
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

**Mainfeed Loading:**
```kotlin
// Load cached tweets for mainfeed from appUser.mid
suspend fun loadCachedTweets(startRank: Int, count: Int): List<Tweet> {
    dao.getCachedTweetsByUser(appUser.mid, startRank, count)
}
```

**User Profile Loading:**
```kotlin
// Load cached tweets for user profile from authorId (userId)
suspend fun loadCachedTweetsByAuthor(authorId: MimeiId, startRank: Int, count: Int): List<Tweet> {
    dao.getCachedTweetsByUser(authorId, startRank, count)
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

### After (Current Algorithm)

- All tweets are cached to database
- Mainfeed tweets cached by `appUser.mid`
- User profile tweets cached by `authorId`
- Individual tweets cached by `authorId`
- Cache persists across login/logout
- Offline loading works for both mainfeed and user profiles

## Testing Recommendations

1. **Test Mainfeed Caching:**
   - Load mainfeed, verify tweets cached by `appUser.mid`
   - Verify original tweets cached by `authorId`
   - Check cache after logout (should persist)

2. **Test Profile Caching:**
   - View any user profile, verify tweets cached by `authorId` (userId)
   - Verify original tweets always cached by `authorId`
   - Check offline loading works for user profiles

3. **Test Tweet Updates:**
   - Favorite/bookmark a tweet, verify cached by `authorId`
   - Retweet a tweet, verify retweet cached by `authorId` (retweeter's ID), original by `authorId`
   - Comment on tweet, verify parent cached by `authorId`

4. **Test Cache Persistence:**
   - Logout and login, verify cache persists
   - Check cached tweets are still available offline
   - Verify manual cache clear works correctly

## Files Changed

1. **`HproseInstance.kt`**
   - `getTweetFeed()`: Original tweets by `authorId`, mainfeed by `appUser.mid`
   - `getTweetsByUser()`: All tweets cached by `authorId`
   - `fetchTweet()`: Cache by `authorId`
   - `loadCachedTweets()`: Load mainfeed from `appUser.mid`
   - `loadCachedTweetsByAuthor()`: Load user profile from `authorId`
   - `updateCachedTweet()`: Accept `userId` parameter
   - `toggleFavorite()`, `toggleBookmark()`: Cache by `authorId`
   - `toggleRetweet()`: Retweet by `authorId`, original by `authorId`
   - `uploadComment()`: Cache by `authorId`

2. **`TweetFeedViewModel.kt`**
   - `TweetUploaded` event: Cache new tweets by `appUser.mid` (mainfeed)
   - `loadCachedTweetsOnly()`: Load from `appUser.mid`

3. **`UserViewModel.kt`**
   - `getTweets()`: Load cached tweets from `authorId` when offline
   - `TweetUploaded` event: Cache new tweets by `authorId` (user profile)

4. **`TweetCacheManager.kt`**
   - `saveTweet()`: Removed `shouldCache` parameter, all tweets cached
   - `updateCachedTweet()`: Removed `shouldCache` parameter, all tweets cached

## References

- iOS Implementation: `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/HproseInstance.swift`
- iOS Cache Manager: `/Users/cfa532/Documents/GitHub/Tweet-iOS/Sources/Core/TweetCacheManager.swift`
- Android Cache Manager: `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

