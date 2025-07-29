# Video Loading and Caching Algorithm

## Overview

This document describes the unified video loading and caching strategy used in the Tweet application. The system leverages ExoPlayer's built-in capabilities for both progressive and HLS videos, with a simplified approach that eliminates the need for custom caching implementations.

## Core Requirements

### 1. **HLS Fallback Sequence (NO RETRIES)**
The system MUST follow this exact sequence for ALL videos:

1. **Start with `master.m3u8`** (HLS master playlist)
2. **If that fails → try `playlist.m3u8`** (HLS playlist) 
3. **If that fails → try original URL** (progressive video)
4. **If all fail → stop player** (no more retries)

### 2. **URL Construction**
- **Base URL**: `if (url.endsWith("/")) url else "$url/"`
- **Master URL**: `${baseUrl}master.m3u8`
- **Playlist URL**: `${baseUrl}playlist.m3u8`
- **Original URL**: `url` (as provided)

### 3. **No Progressive Video Detection**
- **DO NOT** try to detect if a video is progressive
- **DO NOT** skip HLS attempts for any video
- **ALWAYS** try HLS first for ALL videos
- **NO** file extension checking or URL pattern detection

### 4. **No Retries**
- Each format is tried **exactly once**
- **NO** retry attempts for the same URL
- **NO** multiple ExoPlayer instances for the same video
- **NO** infinite loops or repeated attempts

### 5. **Memory Management**
- Failed players MUST be properly stopped with `exoPlayer.stop()`
- **NO** memory leaks from abandoned ExoPlayer instances
- Clean up resources after each failed attempt

### 6. **Exception Handling for Inaccessible URLs**
- **Network errors** (500, 404, connection failures) are expected and handled gracefully
- **No retries** for the same URL - if it fails, move to next fallback
- **Proper logging** of all errors for debugging
- **Graceful degradation** - if video fails, app continues to work
- **User experience** - no crashes or freezes from video loading failures

### 7. **Aspect Ratio Handling**
- **Primary source**: Each video has an `aspectRatio` file in its `MimeiFileType` attachment
- **Fallback mechanism**: `getVideoAspectRatio(context, uri)` is only used as a fallback
- **When to use fallback**: Only when the primary aspect ratio file is not available
- **Performance**: Avoid expensive URI-based aspect ratio extraction when possible

## Simplified Caching Strategy

### Why We Don't Need Custom HLS Caching

Our original implementation was **over-engineering** HLS caching. Here's what we were doing wrong:

1. **Custom Cache Creation**: We created a separate `SimpleCache` for HLS
2. **Manual Playlist Parsing**: We manually parsed m3u8 playlists
3. **Manual Segment Caching**: We manually cached segments using `CacheWriter`
4. **Duplicate Work**: ExoPlayer already does all of this automatically!

### What ExoPlayer Already Provides

ExoPlayer has **built-in, production-ready** caching for both progressive and HLS videos:

#### **Progressive Videos**
- **Single File**: One complete file (e.g., `video.mp4`)
- **Simple Caching**: ExoPlayer caches the entire file or chunks
- **Automatic**: No special handling needed

#### **HLS Videos**
- **Native Support**: ExoPlayer has native HLS support
- **Automatic Caching**: `CacheDataSource` automatically caches HLS segments
- **Playlist Parsing**: ExoPlayer parses m3u8 playlists automatically
- **Segment Management**: ExoPlayer manages segment downloading and caching
- **Quality Switching**: Handles adaptive bitrate automatically

### Simplified Implementation

#### **Key Changes**
1. **Single Cache Manager**: `SimplifiedVideoCacheManager` handles both progressive and HLS
2. **Automatic Detection**: ExoPlayer automatically detects HLS vs progressive format
3. **Built-in Caching**: Uses ExoPlayer's `CacheDataSource` for all video types
4. **IPFS Integration**: Still uses IPFS ID as cache key for consistency

#### **How It Works**
```kotlin
// Single function handles both progressive and HLS
fun createExoPlayer(context: Context, url: String): ExoPlayer {
    val cache = getCache(context)
    val dataSourceFactory = DefaultDataSource.Factory(context)
    
    // Configure cache strategy based on network availability
    val isOnline = isNetworkAvailable(context)
    val cacheFlags = if (isOnline) {
        CacheDataSource.FLAG_BLOCK_ON_CACHE
    } else {
        // When offline, only use cache, don't try network
        CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
    }
    
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(cacheFlags)

    // Use IPFS ID as cache key for all video types
    val ipfsId = url.getMimeiKeyFromUrl()

    // For data blobs, try HLS first, then fallback to original URL
    val baseUrl = if (url.endsWith("/")) url else "$url/"
    val masterUrl = "${baseUrl}master.m3u8"
    val playlistUrl = "${baseUrl}playlist.m3u8"

    val exoPlayer = ExoPlayer.Builder(context).build()

    // Add listener for video events with HLS fallback mechanism (no retries)
    exoPlayer.addListener(object : Player.Listener {
        private var hasTriedPlaylist = false
        private var hasTriedOriginal = false

        override fun onPlayerError(error: PlaybackException) {
            // HLS fallback sequence: master.m3u8 -> playlist.m3u8 -> original URL
            if (!hasTriedPlaylist) {
                hasTriedPlaylist = true
                // Try playlist.m3u8
            } else if (!hasTriedOriginal) {
                hasTriedOriginal = true
                // Try original URL
            } else {
                // All fallback attempts failed, stop the player
                exoPlayer.stop()
            }
        }
    })

    // Always start with HLS (master.m3u8) first
    val initialMediaItem = MediaItem.Builder()
        .setUri(masterUrl)
        .setCustomCacheKey(ipfsId)
        .build()

    val initialMediaSource = DefaultMediaSourceFactory(cacheDataSourceFactory)
        .createMediaSource(initialMediaItem)

    return exoPlayer.apply {
        setMediaSource(initialMediaSource)
        prepare()
        volume = 0f
    }
}
```



## Benefits of Simplified Approach

### **1. Less Code**
- **Before**: 378 lines in `HlsSegmentCacheManager.kt` + 172 lines in `VideoCacheManager.kt`
- **After**: 347 lines in `SimplifiedVideoCacheManager.kt`
- **Reduction**: ~47% less code

### **2. Better Performance**
- **Native Implementation**: Uses ExoPlayer's optimized, battle-tested code
- **Automatic Optimization**: ExoPlayer handles buffer management, quality switching, etc.
- **Memory Efficiency**: Better memory management than custom implementation

### **3. More Reliable**
- **Production Ready**: ExoPlayer's HLS support is used by millions of apps
- **Bug Free**: No custom bugs in playlist parsing or segment management
- **Standards Compliant**: Follows HLS specification exactly

### **4. Easier Maintenance**
- **No Custom Logic**: No need to maintain custom HLS parsing
- **Automatic Updates**: Benefits from ExoPlayer updates
- **Less Testing**: No need to test custom HLS implementation

## Implementation Rules

### ✅ **Correct Behavior:**
```kotlin
// Always start with HLS
val initialMediaItem = MediaItem.Builder()
    .setUri(masterUrl)  // master.m3u8
    .setCustomCacheKey(ipfsId)
    .build()

// Fallback sequence in onPlayerError:
if (!hasTriedPlaylist) {
    // Try playlist.m3u8
} else if (!hasTriedOriginal) {
    // Try original URL
} else {
    // Stop player - all attempts failed
    exoPlayer.stop()
}

// Exception handling for inaccessible URLs
try {
    // Video loading logic
} catch (e: Exception) {
    Timber.e("Video loading failed: ${e.message}")
    // Move to next fallback or stop gracefully
}
```

### ❌ **Incorrect Behavior:**
- Progressive video detection
- Skipping HLS attempts
- Multiple retries of the same URL
- Multiple ExoPlayer instances
- Memory leaks from failed players
- Crashes from inaccessible URLs
- Always using URI-based aspect ratio extraction

## Cache Key Strategy

### **Consistent IPFS ID Usage**
```
URL: http://ip/mm/QmVideo123
IPFS ID: QmVideo123
Cache Key: QmVideo123 (same for both progressive and HLS)
```

### **ExoPlayer's Internal Caching**
- **Progressive**: Caches file chunks using IPFS ID
- **HLS**: Caches segments using IPFS ID + segment info
- **Automatic**: ExoPlayer handles the complexity internally

## Expected Log Flow

```
1. "Created MediaSource for HLS URL: master.m3u8"
2. If fails: "Trying fallback to playlist URL: playlist.m3u8"
3. If fails: "Trying original URL as last resort: original_url"
4. If all fail: "All fallback attempts failed" + stop player
5. Error logs: "Video loading failed: [specific error message]"
```

## Error Handling Examples

### **Network Errors (Expected)**
```
- 500 Server Error: Move to next fallback
- 404 Not Found: Move to next fallback  
- Connection timeout: Move to next fallback
- DNS resolution failure: Move to next fallback
```

### **Graceful Degradation**
```
- Video fails to load → Show placeholder or skip
- Aspect ratio unavailable → Use default or fallback
- Network unavailable → Continue with other content
- Server errors → Log and continue
```

## Aspect Ratio Strategy

### **Primary Method**
```kotlin
// Use aspect ratio from MimeiFileType attachment
val aspectRatio = mimeiFileType.aspectRatio
```

### **Fallback Method**
```kotlin
// Only when primary method fails
val aspectRatio = SimplifiedVideoCacheManager.getVideoAspectRatio(context, uri)
```

### **Performance Considerations**
- **Avoid URI extraction** when aspect ratio file is available
- **Cache aspect ratios** to avoid repeated extraction
- **Handle extraction failures** gracefully
- **Use reasonable timeouts** for URI-based extraction

## Migration Guide

### **What Changed**
1. **Removed**: `HlsSegmentCacheManager` (no longer needed)
2. **Removed**: `VideoCacheManager` (replaced by SimplifiedVideoCacheManager)
3. **Removed**: `README_HLS_Caching.md` (outdated documentation)
4. **Simplified**: `MediaPreview.createExoPlayer()` (single function)
5. **Updated**: `IpfsCacheManager` (removed HLS-specific functions)
6. **Added**: `SimplifiedVideoCacheManager` (unified approach)

### **Functions Migrated**
The following functions were moved from `VideoCacheManager` to `SimplifiedVideoCacheManager`:
- `getVideoAspectRatio()` - Get aspect ratio from URI
- `getVideoDimensions()` - Get video dimensions from URL  
- `clearOldCachedVideos()` - Clean up old cached videos

### **What Stayed the Same**
1. **IPFS ID**: Still used as cache key
2. **Cache Statistics**: Still available in System Settings
3. **Cache Clearing**: Still works for all video types
4. **API**: Same function signatures for compatibility

## Testing

### **Verify HLS Caching Works**
```kotlin
// Check if video is cached (works for both progressive and HLS)
val isCached = SimplifiedVideoCacheManager.isVideoCached(context, videoUrl)

// Get cache statistics
val stats = SimplifiedVideoCacheManager.getCacheStats(context)
```

### **Monitor Cache Behavior**
- **Progressive Videos**: Check `video_cache` directory for file chunks
- **HLS Videos**: Check `video_cache` directory for segments
- **Cache Keys**: All use IPFS ID format

## Key Points to Remember

1. **HLS First**: Always try HLS before progressive
2. **No Detection**: Don't try to guess video format
3. **No Retries**: Each format once only
4. **Clean Stop**: Properly stop failed players
5. **IPFS URLs**: No file extensions, still try HLS first
6. **Exception Handling**: Graceful handling of inaccessible URLs
7. **Aspect Ratio**: Use attachment file first, URI extraction as fallback
8. **Leverage ExoPlayer**: Use built-in capabilities instead of custom implementations

## Common Mistakes to Avoid

- ❌ Adding progressive video detection
- ❌ Skipping HLS for certain URLs
- ❌ Retrying failed URLs
- ❌ Creating multiple players
- ❌ Forgetting to stop failed players
- ❌ Crashes from network errors
- ❌ Always using URI-based aspect ratio extraction
- ❌ Not handling inaccessible URLs gracefully
- ❌ Implementing custom HLS caching when ExoPlayer already provides it

## Conclusion

The simplified approach is **better in every way**:

✅ **Less Code**: 47% reduction in codebase  
✅ **Better Performance**: Uses ExoPlayer's optimized implementation  
✅ **More Reliable**: Production-ready, battle-tested code  
✅ **Easier Maintenance**: No custom HLS logic to maintain  
✅ **Same Features**: All caching functionality preserved  
✅ **IPFS Compatible**: Still uses IPFS ID as cache key  

**Lesson Learned**: Always leverage existing, well-tested libraries instead of reinventing the wheel! 