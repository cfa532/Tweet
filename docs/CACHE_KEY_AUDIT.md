# Cache Key Audit Report

**Date:** October 14, 2025  
**Purpose:** Verify that all cache keys use media ID (mid) instead of URLs

## Summary

✅ **All cache keys now use media IDs (mid) instead of URLs**

## Audit Results

### 1. ✅ Image Caching (Already Correct)

**Location:** `ImageCacheManager.kt`

**Status:** Already using media IDs correctly

**Key Methods:**
- `getCachedImage(context, mid)` - Uses `mid` as cache key
- `loadOriginalImage(context, imageUrl, mid, isVisible)` - Uses `mid` as cache key
- `downloadAndCacheImage(context, imageUrl, mid)` - Uses `mid` as cache key
- `preloadImages(context, mid, mediaUrl)` - Uses `mid` as cache key

**Cache Key Pattern:**
```kotlin
// Compressed images
cacheKey = mid  // e.g., "QmVideo123"

// Original images
cacheKey = "${mid}_original"  // e.g., "QmVideo123_original"
```

**File Storage:**
```kotlin
File(context.cacheDir, "image_cache/$mid.jpg")
```

### 2. ❌ → ✅ Video Caching (Fixed)

**Location:** `CreateExoPlayer.kt`, `VideoManager.kt`

**Previous Issue:** ExoPlayer's `CacheDataSource.Factory()` was using URLs as cache keys by default

**Problem:** 
- Same video from different nodes = different cache entries
- Example:
  - `http://192.168.1.1:8080/mm/QmVideo123` → Cache key: full URL
  - `http://192.168.1.2:8080/mm/QmVideo123` → Cache key: different full URL
  - Result: Same video cached twice

**Fix Applied:** Created `MediaIdCacheKeyFactory` to extract media ID from URLs

**New Implementation:**
```kotlin
// New file: MediaIdCacheKeyFactory.kt
class MediaIdCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        val url = dataSpec.uri.toString()
        return extractMediaIdFromUrl(url)
    }
    
    private fun extractMediaIdFromUrl(url: String): String {
        // Examples:
        // http://192.168.1.1:8080/mm/QmVideo123 → QmVideo123
        // http://192.168.1.1:8080/ipfs/QmVideo123 → QmVideo123
        // http://192.168.1.1:8080/mm/QmVideo123/master.m3u8 → QmVideo123
        // http://192.168.1.1:8080/mm/QmVideo123/playlist.m3u8 → QmVideo123
    }
}
```

**Changes Made:**

1. **CreateExoPlayer.kt:**
```kotlin
val cacheDataSourceFactory = CacheDataSource.Factory()
    .setCache(cache)
    .setUpstreamDataSourceFactory(upstreamFactory)
    .setCacheKeyFactory(MediaIdCacheKeyFactory()) // ← NEW
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
```

2. **VideoManager.kt (attemptVideoRecovery):**
```kotlin
val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
    .setCache(cache)
    .setUpstreamDataSourceFactory(upstreamFactory)
    .setCacheKeyFactory(MediaIdCacheKeyFactory()) // ← NEW
    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
```

**Result:**
- Same video from any node uses same cache entry
- Cache key = media ID (QmVideo123)
- Efficient storage, no duplicates

### 3. ✅ VideoManager Player Tracking (Already Correct)

**Location:** `VideoManager.kt`

**Status:** Already using media IDs correctly

**Player Management:**
```kotlin
private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()
private val activeVideos = ConcurrentHashMap<MimeiId, Int>()
private val visibleVideos = mutableSetOf<MimeiId>()
private val preloadedVideos = mutableSetOf<MimeiId>()
```

All video player tracking uses `MimeiId` (media ID) as the key.

### 4. ✅ Tweet Caching (Already Correct)

**Location:** `TweetCacheManager.kt`

**Status:** Already using media IDs correctly

**Cache Operations:**
```kotlin
memoryCache[tweet.mid] = cachedTweet
userMemoryCache[user.mid] = user
```

All tweet and user caching uses `mid` as the key.

## Benefits of This Fix

1. **Efficient Storage:** Same video from different nodes = single cache entry
2. **Bandwidth Savings:** No redundant downloads of the same content
3. **Consistent Behavior:** Video playback works the same regardless of source node
4. **Cache Hit Rate:** Higher cache hit rate since media ID is consistent across nodes

## URL → Media ID Mapping Examples

### Video URLs:
```
URL: http://192.168.1.1:8080/mm/QmVideo123
Cache Key: QmVideo123

URL: http://192.168.1.2:8080/mm/QmVideo123  (different node, same video)
Cache Key: QmVideo123  (same cache entry ✓)

URL: http://192.168.1.1:8080/mm/QmVideo123/master.m3u8
Cache Key: QmVideo123

URL: http://192.168.1.1:8080/mm/QmVideo123/playlist.m3u8
Cache Key: QmVideo123
```

### Image URLs:
```
URL: http://192.168.1.1:8080/mm/QmImage456
Mid passed: QmImage456
Cache Key: QmImage456

URL: http://192.168.1.2:8080/mm/QmImage456  (different node, same image)
Mid passed: QmImage456
Cache Key: QmImage456  (same cache entry ✓)
```

## Testing Recommendations

1. **Test Cross-Node Caching:**
   - Play same video from different nodes
   - Verify only one cache entry is created
   - Check cache size doesn't increase

2. **Test HLS Videos:**
   - Verify master.m3u8 and playlist.m3u8 use same cache key
   - Check that segments are cached under correct media ID

3. **Monitor Cache Size:**
   - Before fix: Multiple entries for same video
   - After fix: Single entry per unique video

## Files Changed

1. **NEW:** `/app/src/main/java/us/fireshare/tweet/widget/MediaIdCacheKeyFactory.kt`
   - Custom cache key factory for ExoPlayer
   - Extracts media ID from URLs

2. **MODIFIED:** `/app/src/main/java/us/fireshare/tweet/widget/CreateExoPlayer.kt`
   - Added `.setCacheKeyFactory(MediaIdCacheKeyFactory())`

3. **MODIFIED:** `/app/src/main/java/us/fireshare/tweet/widget/VideoManager.kt`
   - Added `.setCacheKeyFactory(MediaIdCacheKeyFactory())` in `attemptVideoRecovery()`

## Build Status

✅ **Build Successful** - All changes compile without errors

```
BUILD SUCCESSFUL in 24s
47 actionable tasks: 12 executed, 35 up-to-date
```

## Conclusion

All cache keys in the codebase now correctly use media IDs (mid) instead of URLs. This ensures:
- Efficient caching across different nodes
- No duplicate cache entries for the same media
- Consistent behavior regardless of media source

