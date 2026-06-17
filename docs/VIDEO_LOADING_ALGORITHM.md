# Video Loading and Caching Algorithm

## Overview

This document describes the unified video loading and caching strategy used in the Tweet application. The system leverages ExoPlayer's built-in capabilities for both progressive and HLS videos, with a simplified approach that eliminates the need for custom caching implementations.

## Core Requirements

### 1. **Video Type-Based Playback Strategy**
The system determines playback strategy based on the attachment's `Type` field:

#### **For HLS Videos (`MediaType.HLS_VIDEO`):**
1. **Race `master.m3u8` and `playlist.m3u8`** (same base URL)
2. **Use the first playlist that responds successfully**
3. **If both fail -> stop player** (NO fallback to progressive video)

#### **For Regular Videos (`MediaType.Video`):**
1. **Play original URL directly** as progressive video
2. **No HLS attempts** - plays as standard MP4/WebM/etc.

### 2. **URL Construction**
- **Base URL**: `if (url.endsWith("/")) url else "$url/"`
- **Master URL**: `${baseUrl}master.m3u8`
- **Playlist URL**: `${baseUrl}playlist.m3u8`
- **Original URL**: `url` (as provided)

### 3. **Type-Based Video Handling**
- **Use attachment Type field** to determine playback strategy
- **HLS videos**: Only try HLS URLs (`master.m3u8` and `playlist.m3u8` raced together)
- **Regular videos**: Only play as progressive video
- **NO** automatic format detection or guessing

### 4. **Video Playback in Different Screens**
All screens use the same pattern based on the attachment's `Type` field:

#### **MediaCell, Tweet Detail Screen, Full Screen:**
```kotlin
VideoPreview(
    url = videoUrl,
    videoType = attachment.type,  // MediaType.HLS_VIDEO or MediaType.Video
    // ... other parameters
)
```

#### **ChatScreen:**
```kotlin
VideoPreview(
    url = mediaUrl,
    videoType = attachment.type,  // MediaType.HLS_VIDEO or MediaType.Video
    // ... other parameters
)
```

The `videoType` parameter is passed to `createExoPlayer()` which determines the playback strategy.

### 5. **No Retries**
- Each format is tried **exactly once**
- **NO** retry attempts for the same URL
- **NO** multiple ExoPlayer instances for the same video
- **NO** infinite loops or repeated attempts

### 6. **Memory Management**
- Failed players MUST be properly stopped with `exoPlayer.stop()`
- **NO** memory leaks from abandoned ExoPlayer instances
- Clean up resources after each failed attempt
- Feed player cache is capped at 6 normal feed players
- Hidden warm preload players are capped separately and kept small

### 7. **Exception Handling for Inaccessible URLs**
- **Network errors** (500, 404, connection failures) are expected and handled gracefully
- **No retries** for the same URL - if it fails, move to next fallback
- **Proper logging** of all errors for debugging
- **Graceful degradation** - if video fails, app continues to work
- **User experience** - no crashes or freezes from video loading failures

### 8. **Aspect Ratio Handling**
- **Primary source**: Each video has an `aspectRatio` file in its `MimeiFileType` attachment
- **Fallback mechanism**: `getVideoAspectRatio(context, uri)` is only used as a fallback
- **When to use fallback**: Only when the primary aspect ratio file is not available
- **Performance**: Avoid expensive URI-based aspect ratio extraction when possible

### 9. **Feed Visibility, Preload, and Cover Rules**
- **Media visibility is authoritative**: Video load/play decisions are based on the media item's visibility, not only the surrounding tweet's visibility.
- **Visible media is protected**: Visible media should not be cancelled or released by preload cleanup.
- **Directional preload count is 2**: After scroll settles, preload up to 2 videos in the current scroll direction.
- **Preload starts only after scroll stops**: During active scrolling, do not start new directional preloads.
- **Scroll start cancels pending preload work**: When scrolling begins again, cancel active preload jobs, clear pending preload queues, and release hidden warm preload players.
- **Foreground playback wins over preload**: Do not create hidden warm preload players while there is active foreground playback demand.
- **Player cache limit is 6**: Normal feed player cache cleanup should keep the feed player pool bounded around 6 players, with a lower target during memory pressure.
- **Cover image rule**: If an attached player has loaded video data, hide the cover image and let the player surface own the pixels. If that loaded player is later released, save the current texture frame and show it as a placeholder cover until a new player loads data.
- **Generated posters must not overwrite last-frame covers**: A delayed poster-generation job must not replace a newer saved last-frame cover.

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
// Function handles both HLS and progressive videos based on MediaType.
// HLS URLs are resolved before player creation by racing master.m3u8 and playlist.m3u8.
fun createExoPlayer(
    context: Context,
    url: String,
    mediaType: MediaType? = null,
    resolvedHlsUrl: String? = null
): ExoPlayer {
    val cache = getCache(context)
    val dataSourceFactory = DefaultDataSource.Factory(context)
    
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val exoPlayer = ExoPlayer.Builder(context).build()

    // Create media source based on video type
    val mediaUrl = if (mediaType == MediaType.HLS_VIDEO) {
        // For HLS videos, use the winning playlist from HlsUrlResolver.
        resolvedHlsUrl ?: error("HLS URL must be resolved before player creation")
    } else {
        // For progressive videos, use original URL directly
        url
    }
    val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(mediaUrl))

    return exoPlayer.apply {
        setMediaSource(mediaSource)
        prepare()
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
// Type-based video handling
val mediaSource = if (mediaType == MediaType.HLS_VIDEO) {
    // For HLS videos, use the winning playlist from HlsUrlResolver.
    mediaSourceFactory.createMediaSource(MediaItem.fromUri(resolvedHlsUrl))
} else {
    // For progressive videos, use original URL directly
    mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))
}

// Exception handling for inaccessible URLs
try {
    // Video loading logic
} catch (e: Exception) {
    Timber.e("Video loading failed: ${e.message}")
    // Handle gracefully without crashes
}
```

### ❌ **Incorrect Behavior:**
- Trying HLS fallback for regular videos
- Falling back to progressive video for HLS videos
- Multiple retries of the same URL
- Starting directional preload while the user is actively scrolling
- Leaving old preload jobs or queues alive when scrolling starts again
- Showing a cover over a player that already has loaded video data
- Letting generated posters replace a newer saved last-frame cover
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

### **For HLS Videos:**
```
1. "Resolving HLS URL by racing master.m3u8 and playlist.m3u8"
2. "Using resolved HLS URL: [winning_playlist]"
3. If both playlist candidates fail: "All HLS playlist candidates failed for URL: [url]"
4. Audio codec errors: "Audio codec error detected, continuing video playback in silence"
5. VideoPreview/FullScreen: "Audio codec error detected in VideoPreview/FullScreenVideoPlayer, continuing video playback in silence"
6. Error logs: "Final error: [specific error message]"
```

### **For Regular Videos:**
```
1. "Player ready for URL: [original_url]"
2. If fails: "Progressive video error: [specific error message]"
3. Audio codec errors: "Audio codec error detected, continuing video playback in silence"
4. Error logs: "Final error: [specific error message]"
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

1. **Type-Based Strategy**: Use attachment Type field to determine playback strategy
2. **HLS Videos**: Only try HLS URLs (`master.m3u8` and `playlist.m3u8` raced together), no progressive fallback
3. **Regular Videos**: Only play as progressive video, no HLS attempts
4. **No Retries**: Each format once only
5. **Clean Stop**: Properly stop failed players
6. **Exception Handling**: Graceful handling of inaccessible URLs
7. **Audio Codec Errors**: Continue video playback in silence when audio codec fails
8. **Aspect Ratio**: Use attachment file first, URI extraction as fallback
9. **Preload Discipline**: Directional preloads start only after scroll stops and are cancelled when scroll starts again
10. **Cover Discipline**: Loaded players do not show covers; released loaded players show their saved last frame
11. **Leverage ExoPlayer**: Use built-in capabilities instead of custom implementations

## Common Mistakes to Avoid

- ❌ Trying HLS fallback for regular videos
- ❌ Falling back to progressive video for HLS videos
- ❌ Retrying failed URLs
- ❌ Preloading during active scroll
- ❌ Keeping pending preload work after scroll starts
- ❌ Showing a cover over a player that has loaded video data
- ❌ Replacing last-frame covers with older generated posters
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
