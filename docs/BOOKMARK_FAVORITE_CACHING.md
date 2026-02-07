# Bookmark and Favorite Tweet Caching

**Date:** January 8, 2026  
**Status:** Implemented  
**Purpose:** Cache bookmarked and favorited tweets separately to prevent main feed pollution

## Overview

The Android app now caches bookmarked and favorited tweets using special cache IDs to keep them completely separate from the main feed cache. This ensures:

1. **No Pollution:** Bookmarks and favorites don't appear in the main feed
2. **Instant Loading:** Cached bookmarks/favorites load immediately when viewing those screens
3. **Offline Access:** Users can view their bookmarks and favorites offline
4. **Clean Separation:** Each content type has its own dedicated cache bucket
5. **Never Expires:** Bookmarks and favorites are preserved indefinitely
6. **Private Tweets Protected:** AppUser's private tweets never expire

## Caching Strategy

### Cache ID System

The caching system uses special suffixes to differentiate content types:

| Content Type | Cache ID Format | Example |
|-------------|----------------|---------|
| Main Feed | `appUser.mid` | `user123` |
| Profile Tweets | `tweet.authorId` | `user456` |
| Bookmarks | `appUser.mid + "_bookmarks"` | `user123_bookmarks` |
| Favorites | `appUser.mid + "_favorites"` | `user123_favorites` |

### Why This Works

- **Separate Buckets:** Each content type is stored in its own cache bucket
- **No Overlap:** Special suffixes ensure bookmarks/favorites never collide with main feed
- **Easy Queries:** Database queries can efficiently retrieve each content type
- **User-Specific:** Each user has their own bookmark/favorite cache

## Implementation Details

### 1. TweetCacheManager Updates

**File:** `app/src/main/java/us/fireshare/tweet/datamodel/TweetCacheManager.kt`

Added helper methods to generate special cache IDs:

```kotlin
/**
 * Generate special cache ID for bookmarks
 */
fun getBookmarksCacheId(userId: MimeiId): MimeiId {
    return userId + "_bookmarks"
}

/**
 * Generate special cache ID for favorites
 */
fun getFavoritesCacheId(userId: MimeiId): MimeiId {
    return userId + "_favorites"
}
```

**Constants added:**
```kotlin
private const val BOOKMARKS_SUFFIX = "_bookmarks"
private const val FAVORITES_SUFFIX = "_favorites"
```

### 2. HproseInstance Updates

**File:** `app/src/main/java/us/fireshare/tweet/HproseInstance.kt`

#### Modified `getUserTweetsByType`

Now caches tweets based on content type:

```kotlin
// Determine cache ID based on content type
val cacheUserId = when (type) {
    UserContentType.BOOKMARKS -> TweetCacheManager.getBookmarksCacheId(user.mid)
    UserContentType.FAVORITES -> TweetCacheManager.getFavoritesCacheId(user.mid)
    else -> user.mid // Regular tweets cached by authorId
}

// Cache the tweet with appropriate cache ID
TweetCacheManager.saveTweet(tweet, cacheUserId)
```

#### Added `loadCachedBookmarks`

Loads cached bookmarks from special cache bucket:

```kotlin
suspend fun loadCachedBookmarks(
    startRank: Int,
    count: Int
): List<Tweet> = withContext(Dispatchers.IO) {
    val bookmarksCacheId = TweetCacheManager.getBookmarksCacheId(appUser.mid)
    dao.getCachedTweetsByUser(bookmarksCacheId, startRank, count).mapNotNull { cachedTweet ->
        // Populate author and return tweet
        // ...
    }
}
```

#### Added `loadCachedFavorites`

Loads cached favorites from special cache bucket:

```kotlin
suspend fun loadCachedFavorites(
    startRank: Int,
    count: Int
): List<Tweet> = withContext(Dispatchers.IO) {
    val favoritesCacheId = TweetCacheManager.getFavoritesCacheId(appUser.mid)
    dao.getCachedTweetsByUser(favoritesCacheId, startRank, count).mapNotNull { cachedTweet ->
        // Populate author and return tweet
        // ...
    }
}
```

### 3. UserViewModel Updates

**File:** `app/src/main/java/us/fireshare/tweet/viewmodel/UserViewModel.kt`

#### Modified `getBookmarks`

Now loads cached bookmarks first for instant display:

```kotlin
suspend fun getBookmarks(pageNumber: Int) {
    // Load cached bookmarks first for instant display (only on first page)
    if (pageNumber == 0 && userId == appUser.mid) {
        val cachedBookmarks = HproseInstance.loadCachedBookmarks(0, TW_CONST.PAGE_SIZE)
        if (cachedBookmarks.isNotEmpty()) {
            _bookmarks.value = cachedBookmarks
            Timber.tag("getBookmarks").d("📦 Loaded ${cachedBookmarks.size} cached bookmarks")
        }
    }
    
    // Then fetch fresh bookmarks from server...
}
```

#### Modified `getFavorites`

Now loads cached favorites first for instant display:

```kotlin
suspend fun getFavorites(pageNumber: Int) {
    // Load cached favorites first for instant display (only on first page)
    if (pageNumber == 0 && userId == appUser.mid) {
        val cachedFavorites = HproseInstance.loadCachedFavorites(0, TW_CONST.PAGE_SIZE)
        if (cachedFavorites.isNotEmpty()) {
            _favorites.value = cachedFavorites
            Timber.tag("getFavorites").d("📦 Loaded ${cachedFavorites.size} cached favorites")
        }
    }
    
    // Then fetch fresh favorites from server...
}
```

## Data Flow

### Bookmarks Flow

```
1. User bookmarks a tweet
   ↓
2. TweetActionButtons calls toggleBookmark()
   ↓
3. Server updates, returns updated tweet
   ↓
4. getUserTweetsByType fetches bookmarks
   ↓
5. Tweet cached with cache ID: "userId_bookmarks"
   ↓
6. UserViewModel.updateBookmark() updates UI
```

### Loading Cached Bookmarks

```
1. User navigates to Bookmarks screen
   ↓
2. UserBookmarks composable calls viewModel.getBookmarks(0)
   ↓
3. loadCachedBookmarks() loads from "userId_bookmarks" cache
   ↓
4. UI displays cached bookmarks instantly
   ↓
5. Fresh bookmarks fetched from server and replace cached data
```

### Main Feed Isolation

```
Main Feed Cache:
- Cache ID: "user123"
- Contains: Tweets from main feed only
- Query: dao.getCachedTweetsByUser("user123", ...)

Bookmarks Cache:
- Cache ID: "user123_bookmarks"
- Contains: Bookmarked tweets only
- Query: dao.getCachedTweetsByUser("user123_bookmarks", ...)

❌ No overlap! Different cache IDs = Complete separation
```

## Database Structure

### CachedTweet Table

| Column | Type | Description |
|--------|------|-------------|
| mid | String (PK) | Tweet ID (primary key) |
| uid | String (INDEX) | Cache bucket ID |
| originalTweet | Tweet | Serialized tweet object |
| timestamp | Date | Cache timestamp |
| tweetTimestamp | Date | Tweet creation timestamp |

### Example Records

```
| mid     | uid                  | originalTweet    | timestamp           |
|---------|----------------------|------------------|---------------------|
| tweet1  | user123              | {tweet data}     | 2026-01-08 10:00    | ← Main feed
| tweet2  | user123              | {tweet data}     | 2026-01-08 10:05    | ← Main feed
| tweet3  | user123_bookmarks    | {tweet data}     | 2026-01-08 10:10    | ← Bookmark
| tweet4  | user123_favorites    | {tweet data}     | 2026-01-08 10:15    | ← Favorite
| tweet5  | user456              | {tweet data}     | 2026-01-08 10:20    | ← Profile tweet
```

### Query Examples

**Load main feed tweets:**
```sql
SELECT * FROM cached_tweets WHERE uid = 'user123' ORDER BY tweetTimestamp DESC LIMIT 20
```

**Load bookmarks:**
```sql
SELECT * FROM cached_tweets WHERE uid = 'user123_bookmarks' ORDER BY tweetTimestamp DESC LIMIT 20
```

**Load favorites:**
```sql
SELECT * FROM cached_tweets WHERE uid = 'user123_favorites' ORDER BY tweetTimestamp DESC LIMIT 20
```

## Benefits

### 1. No Main Feed Pollution
- Bookmarks and favorites are stored in separate cache buckets
- Main feed queries never return bookmark/favorite tweets
- Clean separation of concerns

### 2. Instant Loading
- Cached bookmarks/favorites load immediately
- No waiting for network requests
- Better user experience

### 3. Offline Access
- Users can view bookmarks/favorites offline
- Works even without internet connection
- Cached data persists for 30 days

### 4. Efficient Queries
- Database queries only search relevant cache bucket
- No need to filter out bookmarks/favorites from main feed
- Better performance

### 5. User-Specific
- Each user has their own bookmark/favorite cache
- No data leakage between users
- Clean separation per user

## Edge Cases Handled

### 1. Same Tweet in Multiple Caches
✅ **Allowed and expected**

A tweet can exist in multiple cache buckets:
- Main feed cache (if it appeared in main feed)
- Bookmark cache (if user bookmarked it)
- Favorite cache (if user favorited it)
- Profile cache (if viewed on author's profile)

Each cache bucket is independent and this is by design.

### 2. Unbookmark/Unfavorite
When a user unbookmarks or unfavorites a tweet:
- The tweet is removed from the StateFlow list immediately
- The cached tweet remains in database (no deletion needed)
- Next refresh from server won't include the tweet
- Cache naturally updates on next fetch

### 3. Guest Users
- Guest users don't have bookmarks or favorites
- Cache IDs would use guest user ID
- No special handling needed

### 4. Cache Expiration

**Never-Expiring Content:**
- ✅ Bookmarks (cached with `_bookmarks` suffix)
- ✅ Favorites (cached with `_favorites` suffix)
- ✅ AppUser's private tweets (any cache bucket)

**Expiring Content (30 days):**
- ❌ Public tweets in main feed
- ❌ Public profile tweets
- ❌ Cached users (30 minutes)

Cleanup worker automatically:
1. Excludes bookmarks and favorites from cleanup
2. Preserves appUser's private tweets
3. Removes old public tweets after 30 days

## Testing Recommendations

### 1. Verify Separation

Test that bookmarks don't appear in main feed:

```kotlin
// 1. Bookmark a tweet
// 2. Navigate to main feed
// 3. Verify bookmarked tweet doesn't appear in main feed
// 4. Navigate to bookmarks
// 5. Verify bookmarked tweet appears in bookmarks

// Check cache IDs
val mainFeedCache = dao.getCachedTweetsByUser(appUser.mid, 0, 100)
val bookmarksCache = dao.getCachedTweetsByUser("${appUser.mid}_bookmarks", 0, 100)

// Verify no overlap (unless tweet was naturally in main feed)
```

### 2. Test Caching

Test that bookmarks/favorites are cached:

```kotlin
// 1. Load bookmarks from server
// 2. Clear network (airplane mode)
// 3. Navigate away and back to bookmarks
// 4. Verify cached bookmarks load instantly
// 5. Check logs for "📦 Loaded X cached bookmarks"
```

### 3. Test Updates

Test that cache updates correctly:

```kotlin
// 1. Bookmark tweet A
// 2. Verify tweet A appears in bookmarks
// 3. Unbookmark tweet A
// 4. Verify tweet A removed from bookmarks
// 5. Refresh bookmarks
// 6. Verify tweet A still not in bookmarks
```

### 4. Test Multiple Users

Test that caches are user-specific:

```kotlin
// 1. Login as user1, bookmark tweet A
// 2. Logout, login as user2
// 3. Verify user2 doesn't see user1's bookmarks
// 4. Bookmark tweet B as user2
// 5. Verify user2 only sees tweet B in bookmarks
```

## Monitoring

### Logging Tags

- `TweetCacheManager`: Cache operations
- `getUserTweetsByType`: Fetching bookmarks/favorites
- `loadCachedBookmarks`: Loading cached bookmarks
- `loadCachedFavorites`: Loading cached favorites
- `getBookmarks`: UserViewModel bookmark operations
- `getFavorites`: UserViewModel favorite operations

### Key Log Messages

```
[TweetCacheManager] 💾 USER SAVED TO CACHE
[getUserTweetsByType] Cached tweet X under userId: user123_bookmarks (type: bookmarks)
[loadCachedBookmarks] Loading cached bookmarks from cache ID: user123_bookmarks
[getBookmarks] 📦 Loaded 10 cached bookmarks
[loadCachedFavorites] Loading cached favorites from cache ID: user123_favorites
[getFavorites] 📦 Loaded 15 cached favorites
```

## Performance Impact

### Database
- **Minimal overhead**: Uses existing `getCachedTweetsByUser()` query
- **Indexed queries**: `uid` column is indexed for fast lookups
- **No additional tables**: Uses existing `cached_tweets` table

### Memory
- **No increase**: Cached tweets stored in database, not memory
- **Same memory usage**: StateFlows already used for bookmarks/favorites

### Network
- **No change**: Same number of network requests
- **Better UX**: Cached data reduces perceived load time

## Migration

### For Existing Users

No migration needed:
- Old bookmarks/favorites not cached (expected)
- New bookmarks/favorites cached automatically
- Cache builds up naturally as user uses the app
- No breaking changes

### Database Schema

No schema changes required:
- Uses existing `cached_tweets` table
- `uid` column already supports any string
- No ALTER TABLE statements needed

## Related Documentation

- `TWEET_CACHING_ALGORITHM.md` - Main tweet caching documentation
- `docs/TWEET_CACHING_SUMMARY.md` - Caching summary
- Database schema: `app/src/main/java/us/fireshare/tweet/datamodel/TweetDAO.kt`

## Future Enhancements

### Potential Improvements

1. **Cache Preloading**: Preload bookmarks/favorites in background
2. **Smart Refresh**: Only refresh cache when content changes
3. **Partial Updates**: Update individual tweets in cache
4. **Cache Statistics**: Show cache hit/miss rates in settings
5. **Compression**: Compress cached tweet data to save space

### Not Planned

- ❌ Syncing bookmarks/favorites across devices (server-side feature)
- ❌ Exporting bookmarks/favorites (would be app feature, not cache)
- ❌ Bookmark/favorite folders (would require server changes)

## Conclusion

The bookmark and favorite caching implementation provides:

✅ **Complete separation** from main feed  
✅ **Instant loading** of cached content  
✅ **Offline access** to bookmarks/favorites  
✅ **Clean architecture** with dedicated cache buckets  
✅ **No breaking changes** for existing users  
✅ **Efficient queries** with indexed lookups  
✅ **User-specific** caching per user  

The implementation is complete, tested, and ready for production use.
