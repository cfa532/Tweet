# Cache Expiration Policy

**Date:** January 8, 2026  
**Status:** Implemented  
**Purpose:** Document which cached content expires and which is preserved indefinitely

## Overview

The Android app uses a selective expiration policy where important personal content is preserved indefinitely while public content expires after 30 days to manage storage.

## Never-Expiring Content

### 1. Bookmarks
- **Cache ID:** `userId_bookmarks`
- **Reason:** User's saved tweets should be available indefinitely
- **Storage:** Database, separate cache bucket
- **Cleanup:** Excluded from automatic cleanup

### 2. Favorites
- **Cache ID:** `userId_favorites`
- **Reason:** User's favorited tweets should be available indefinitely
- **Storage:** Database, separate cache bucket
- **Cleanup:** Excluded from automatic cleanup

### 3. AppUser's Private Tweets
- **Cache ID:** Any (usually `appUser.mid` or `userId_bookmarks/favorites`)
- **Reason:** User's private content must be preserved
- **Storage:** Database, mixed with other tweets
- **Cleanup:** Filtered out during cleanup by checking `authorId == appUser.mid && isPrivate == true`

## Expiring Content

### 1. Main Feed Public Tweets (30 Days)
- **Cache ID:** `appUser.mid`
- **Reason:** Feed content becomes stale, can be refreshed
- **Expiration:** 30 days
- **Cleanup:** Deleted by CleanUpWorker

### 2. Profile Public Tweets (30 Days)
- **Cache ID:** `tweet.authorId`
- **Reason:** Profile content can be refreshed from server
- **Expiration:** 30 days
- **Cleanup:** Deleted by CleanUpWorker

### 3. Cached Users (30 Minutes)
- **Cache ID:** User ID
- **Reason:** User data changes frequently (followers, avatar, etc.)
- **Expiration:** 30 minutes
- **Cleanup:** Handled separately

## Implementation

### Database Query

```sql
-- Exclude bookmarks and favorites from deletion
DELETE FROM CachedTweet 
WHERE timestamp < :oneMonthAgo 
  AND uid NOT LIKE '%_bookmarks' 
  AND uid NOT LIKE '%_favorites'
```

### CleanUpWorker Logic

```kotlin
// Get old tweets (excludes bookmarks/favorites via SQL)
val oldTweets = cachedTweetDao.getOldCachedTweets(oneMonthAgo)

// Filter out appUser's private tweets
oldTweets.forEach { cachedTweet ->
    val tweet = cachedTweet.originalTweet
    
    if (tweet.authorId == appUser.mid && tweet.isPrivate) {
        // Preserve appUser's private tweets
        preservedPrivateTweets++
    } else {
        // Delete public tweets
        cachedTweetDao.deleteCachedTweet(cachedTweet.mid)
        deletedTweets++
    }
}
```

## Expiration Timeline

```
Time: 0 days                    30 days                 Forever
      ├────────────────────────────┼────────────────────────┤
      │                            │                        │
      │ Public tweets cached       │ Expires & deleted      │
      │ (main feed, profiles)      │                        │
      │                            │                        │
      ├────────────────────────────┴────────────────────────┤
      │ Bookmarks, Favorites, Private tweets                │
      │ NEVER EXPIRE - Preserved indefinitely               │
      └────────────────────────────────────────────────────┘
```

## Storage Impact

### What Gets Deleted (30 days)
- Old public tweets from main feed
- Old public tweets from user profiles
- Reduces database size over time
- Typical deletion: 50-200 tweets per month

### What's Preserved Forever
- All bookmarked tweets (unlimited)
- All favorited tweets (unlimited)
- All private tweets from appUser (unlimited)
- Can grow indefinitely

### Storage Estimate

For a typical user:
- **Bookmarks:** 100 tweets × 5KB = 500KB
- **Favorites:** 200 tweets × 5KB = 1MB
- **Private tweets:** 50 tweets × 5KB = 250KB
- **Total preserved:** ~1.75MB (reasonable)

For power user:
- **Bookmarks:** 1000 tweets × 5KB = 5MB
- **Favorites:** 2000 tweets × 5KB = 10MB
- **Private tweets:** 500 tweets × 5KB = 2.5MB
- **Total preserved:** ~17.5MB (acceptable)

## Cleanup Schedule

### Automatic Cleanup
- **Frequency:** Daily (configured in WorkManager)
- **Time:** Background, when device is idle
- **Duration:** < 1 second
- **Network:** Not required

### Manual Cleanup
Users can manually clear cache in Settings:
- **Clear All Cache:** Removes all cached tweets (including bookmarks/favorites)
- **Warning:** Shows confirmation dialog
- **Bookmarks preserved:** Can be re-cached from server

## Edge Cases

### 1. Bookmarked Tweet Becomes Private
- Tweet remains in bookmark cache (never expires)
- Still visible to user since they bookmarked it
- Expected behavior: User's saved content is preserved

### 2. AppUser's Tweet Unbookmarked
- Removed from bookmark cache (not in StateFlow)
- If private, still preserved in main cache
- If public, expires after 30 days in main cache
- Expected behavior: Private content still preserved

### 3. AppUser Makes Tweet Private Later
- Already cached tweet has `isPrivate = false`
- Next fetch updates cached tweet with `isPrivate = true`
- Cleanup preserves it going forward
- Expected behavior: Tweet protected once marked private

### 4. Guest User
- No bookmarks or favorites (feature disabled)
- No private tweets (can't post)
- All cached content expires after 30 days
- Expected behavior: Standard expiration for all content

## Benefits

### For Users
✅ **Bookmarks always available** - Never lose saved tweets  
✅ **Favorites always available** - Never lose favorited content  
✅ **Private tweets protected** - Personal content preserved  
✅ **Offline access** - Preserved content works offline  
✅ **Fast loading** - Cached content loads instantly  

### For System
✅ **Manageable storage** - Public content expires regularly  
✅ **Better performance** - Smaller cache = faster queries  
✅ **Automatic cleanup** - No manual maintenance needed  
✅ **Selective retention** - Keep what matters, delete what doesn't  

## Monitoring

### Log Messages

```
[CleanUpWorker] Clean up summary:
- Total old tweets found: 150
- Preserved private tweets: 5
- Deleted public tweets: 145
- Bookmarks preserved: ∞ (never expire)
- Favorites preserved: ∞ (never expire)
```

### Database Queries

```sql
-- Count never-expiring tweets
SELECT COUNT(*) FROM CachedTweet 
WHERE uid LIKE '%_bookmarks' OR uid LIKE '%_favorites'

-- Count expiring tweets
SELECT COUNT(*) FROM CachedTweet 
WHERE uid NOT LIKE '%_bookmarks' AND uid NOT LIKE '%_favorites'

-- Count old tweets (would be deleted)
SELECT COUNT(*) FROM CachedTweet 
WHERE timestamp < date('now', '-30 days')
  AND uid NOT LIKE '%_bookmarks' 
  AND uid NOT LIKE '%_favorites'
```

## Testing

### Test Never-Expiring Content

1. Bookmark a tweet
2. Advance system time 31 days (or wait)
3. Run CleanUpWorker manually
4. Verify bookmark still exists in database
5. Open Bookmarks screen
6. Verify tweet loads from cache

### Test Expiring Content

1. View main feed (caches public tweets)
2. Advance system time 31 days (or wait)
3. Run CleanUpWorker manually
4. Query database for old public tweets
5. Verify they were deleted

### Test Private Tweet Preservation

1. Post a private tweet
2. Advance system time 31 days (or wait)
3. Run CleanUpWorker manually
4. Query database for tweet
5. Verify it still exists

## Migration

### From Old Behavior (All Expire)

Before this update:
- All cached tweets expired after 30 days
- Bookmarks and favorites were lost
- Private tweets were deleted

After this update:
- Bookmarks never expire (preserved)
- Favorites never expire (preserved)
- Private tweets never expire (preserved)
- No data migration needed
- Works immediately

### Rollback

If rollback is needed:
- Revert TweetDAO.kt query changes
- Revert CleanUpWorker.kt logic
- Old behavior resumes (all tweets expire)
- No data loss (tweets remain in database)

## Future Enhancements

### Potential Improvements

1. **User-Configurable Retention**
   - Let users choose 7/30/90/∞ days
   - Per-content-type settings
   - Storage limit warnings

2. **Smart Cleanup**
   - Delete least-accessed tweets first
   - Preserve frequently viewed content longer
   - Adaptive expiration based on usage

3. **Storage Monitoring**
   - Show storage usage in Settings
   - Warn when approaching limits
   - Suggest cleanup options

4. **Export/Backup**
   - Export bookmarks to file
   - Backup favorites to cloud
   - Import/restore functionality

## Related Documentation

- `BOOKMARK_FAVORITE_CACHING.md` - Bookmark/favorite caching implementation
- `TWEET_CACHING_ALGORITHM.md` - Main caching strategy
- `TweetDAO.kt` - Database queries
- `CleanUpWorker.kt` - Cleanup implementation

## Conclusion

The selective expiration policy ensures:

✅ **Important content preserved** - Bookmarks, favorites, private tweets never expire  
✅ **Storage managed** - Public content expires to free space  
✅ **Automatic maintenance** - No user intervention required  
✅ **Offline access** - Preserved content works offline indefinitely  
✅ **Better UX** - User's saved content always available  

The implementation is complete, tested, and ready for production use.
