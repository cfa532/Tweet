# ExoPlayer Architecture Documentation

**Last Updated:** December 2024

## Overview

This document describes the ExoPlayer sharing architecture in the Tweet application, explaining how different video viewing contexts manage ExoPlayer instances for optimal performance and user experience.

## Architecture Summary

The app uses **two distinct ExoPlayer management strategies** based on the viewing context:

1. **MediaItemView ↔ FullScreen**: **Shared ExoPlayer instances** for seamless transitions
2. **TweetDetailView**: **Independent ExoPlayer instances** for isolated playback control

## 1. MediaItemView ↔ FullScreen Sharing

### Architecture Pattern
MediaItemView (in feeds) and FullScreen mode **share the same ExoPlayer instance** through VideoManager's transfer mechanism.

### Implementation Details

#### MediaItemView Video Creation
```kotlin
// In MediaItemView.kt
VideoPreview(
    url = videoUrl,
    videoMid = videoMid,  // Key for VideoManager tracking
    videoType = attachment.type,
    // ... other parameters
)
```

#### VideoManager Player Management
```kotlin
// In VideoManager.kt
fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null): ExoPlayer {
    return videoPlayers.getOrPut(videoMid) {
        createExoPlayer(context, videoUrl, videoType ?: MediaType.Video)
    }.also { player ->
        // Reset player state when reusing an existing player
        if (isReusing) {
            resetPlayerState(player)
        }
    }
}
```

#### FullScreen Transfer Mechanism
```kotlin
// In MediaBrowser.kt and ChatScreen.kt
val existingPlayer = VideoManager.transferToFullScreen(videoMid)

if (existingPlayer != null) {
    // Use existing player for seamless transition
    FullScreenVideoPlayer(
        existingPlayer = existingPlayer,
        videoItem = videoItem,
        onClose = {
            // Return player back to VideoManager when closed
            VideoManager.returnFromFullScreen(videoMid)
        }
    )
}
```

#### Transfer Functions
```kotlin
// In VideoManager.kt
fun transferToFullScreen(videoMid: MimeiId): ExoPlayer? {
    return videoPlayers[videoMid]?.also { player ->
        Timber.tag("transferToFullScreen").d("Transferring player for $videoMid to full-screen")
        currentFullScreenVideoMid = videoMid
    }
}

fun returnFromFullScreen(videoMid: MimeiId) {
    videoPlayers[videoMid]?.let { player ->
        Timber.tag("returnFromFullScreen").d("Returning player for $videoMid from full-screen")
        currentFullScreenVideoMid = null
    }
}
```

### Benefits of Sharing
- **Seamless Transitions**: No interruption when going fullscreen
- **Position Preservation**: Maintains current playback position
- **Memory Efficiency**: Reuses existing player instance
- **State Continuity**: Preserves buffered content and playback state

## 2. TweetDetailView Independent Players

### Architecture Pattern
TweetDetailView creates **independent ExoPlayer instances** that don't interfere with feed videos.

### Implementation Details

#### TweetDetailView Video Creation
```kotlin
// In TweetDetailBody.kt
MediaItemView(
    // ... parameters
    useIndependentVideoMute = true  // Independent mute state
)
```

#### VideoPreview with Independent Mute
```kotlin
// In VideoPreview.kt
VideoPreview(
    url = videoUrl,
    videoMid = videoMid,
    videoType = videoType,
    useIndependentMuteState = useIndependentVideoMute  // Independent mute control
)
```

#### Independent Player Creation
```kotlin
// In VideoPreview.kt
val exoPlayer = remember(videoMid) {
    val player = if (videoMid != null) {
        VideoManager.getVideoPlayer(context, videoMid, url, videoType)
    } else {
        createExoPlayer(context, url, videoType ?: MediaType.Video)
    }
    // Independent mute state handling
    player
}
```

### Benefits of Independence
- **Isolated Control**: Detail view videos don't affect feed videos
- **Independent Mute State**: Detail videos can be unmuted while feed stays muted
- **Separate Playback**: Different playback behaviors per context
- **No Conflicts**: Prevents interference between different viewing contexts

## 3. Mute State Management

### Context-Aware Mute Behavior

| Context | Mute State | Syncs with Global | Affects Global |
|---------|------------|-------------------|----------------|
| MediaItem (Feeds) | Global | ✅ Yes | ✅ Yes |
| TweetDetailView | 🔊 Unmuted | ❌ No | ❌ No |
| FullScreenPlayer | 🔊 Unmuted | ❌ No | ❌ No |

### Implementation
```kotlin
// In VideoPreview.kt
var isMuted by remember(videoMid) { 
    mutableStateOf(if (useIndependentMuteState) false else preferenceHelper.getSpeakerMute()) 
}
```

## 4. VideoManager Architecture

### Player Storage
```kotlin
// Thread-safe map to store ExoPlayer instances by video mid
private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()
```

### Player Lifecycle Management
```kotlin
// Track which videos are currently being used
private val activeVideos = ConcurrentHashMap<MimeiId, Int>()

// Track which videos are currently visible (user is viewing them)
private val visibleVideos = mutableSetOf<MimeiId>()
```

### Full-Screen State Tracking
```kotlin
// Track which video is currently in full-screen mode
private var currentFullScreenVideoMid: MimeiId? = null
```

## 5. Timeout Configuration

### Different Timeouts by Context

#### Initial Video Loading
```kotlin
// In CreateExoPlayer.kt
val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setConnectTimeoutMs(30000) // 30 seconds connection timeout
    .setReadTimeoutMs(30000)    // 30 seconds read timeout
```

#### Video Recovery/Retry
```kotlin
// In VideoManager.kt (for retry scenarios)
val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setConnectTimeoutMs(45000) // 45 seconds connection timeout for thorough retry
    .setReadTimeoutMs(45000)    // 45 seconds read timeout for thorough retry
```

## 6. Retry Mechanism

### Automatic Retries
- **Max Retries**: 3 attempts
- **Retry Delay**: 2 seconds between retries
- **MediaCodec Recovery**: 1 second delay with software decoder fallback
- **Manual Retry**: Available via UI button

### Retry Logic
```kotlin
// In VideoPreview.kt
var retryCount by remember(videoMid) { mutableIntStateOf(0) }
val maxRetries = 3

// Automatic retry for recoverable errors
if (isRecoverableError && retryCount < maxRetries && videoMid != null) {
    retryCount++
    // Retry after delay
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        delay(2000) // Wait 2 seconds before retry
        VideoManager.attemptVideoRecovery(context, videoMid, url, videoType)
    }
}
```

## 7. Video Loading Strategy

### Type-Based Playback
- **HLS Videos**: Try `master.m3u8` → `playlist.m3u8` → stop
- **Regular Videos**: Play original URL directly as progressive video
- **No Format Detection**: Relies on attachment's `Type` field

### URL Construction
```kotlin
// Base URL normalization
val baseUrl = if (url.endsWith("/")) url else "$url/"

// HLS URLs
val masterUrl = "${baseUrl}master.m3u8"
val playlistUrl = "${baseUrl}playlist.m3u8"
```

## 8. Cache Sharing Between Player Instances

### Shared Cache Architecture
**Yes, MediaItemView and TweetDetailView ExoPlayer instances DO share cache** even though they use different player instances. This is achieved through:

1. **Shared Cache Instance**: All ExoPlayer instances use the same `SimpleCache` from VideoManager
2. **Consistent Cache Keys**: `MediaIdCacheKeyFactory` ensures same media uses same cache key
3. **CacheDataSource**: All players use `CacheDataSource` with the shared cache

### Implementation Details

#### Shared Cache Instance
```kotlin
// In VideoManager.kt - Single shared cache for all players
private var videoCache: SimpleCache? = null
private const val CACHE_SIZE_BYTES = 2000L * 1024 * 1024 // 2GB cache size

fun getCache(context: Context): Cache {
    synchronized(cacheLock) {
        if (videoCache == null) {
            val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context)
            videoCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return videoCache ?: throw IllegalStateException("Video cache was not initialized")
    }
}
```

#### Consistent Cache Keys
```kotlin
// In MediaIdCacheKeyFactory.kt - Same cache key for same media
class MediaIdCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        val url = dataSpec.uri.toString()
        return extractMediaIdFromUrl(url)  // Returns media ID (e.g., "QmVideo123")
    }
}

// Examples of cache key generation:
// URL: http://192.168.1.1:8080/mm/QmVideo123 -> Cache Key: "QmVideo123"
// URL: http://192.168.1.2:8080/mm/QmVideo123 -> Cache Key: "QmVideo123" (same!)
// URL: http://192.168.1.1:8080/mm/QmVideo123/master.m3u8 -> Cache Key: "QmVideo123/master.m3u8"
```

#### CacheDataSource Configuration
```kotlin
// In CreateExoPlayer.kt - All players use shared cache
val cache = VideoManager.getCache(context)  // Same cache instance
val cacheDataSourceFactory = CacheDataSource.Factory()
    .setCache(cache)                        // Shared cache
    .setUpstreamDataSourceFactory(upstreamFactory)
    .setCacheKeyFactory(MediaIdCacheKeyFactory()) // Consistent keys
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
```

### Cache Sharing Benefits

#### 1. **Instant Playback**
- MediaItemView downloads and caches video segments
- TweetDetailView immediately plays from cache (no re-download)
- Same applies to fullscreen transitions

#### 2. **Bandwidth Savings**
- Video segments downloaded once, used by all player instances
- HLS segments cached and shared across different viewing contexts
- Progressive videos cached as single files

#### 3. **Cross-Node Cache Sharing**
```kotlin
// Different nodes serving same video share cache:
// Node 1: http://192.168.1.1:8080/mm/QmVideo123 -> Cache Key: "QmVideo123"
// Node 2: http://192.168.1.2:8080/mm/QmVideo123 -> Cache Key: "QmVideo123" (same!)
```

#### 4. **HLS Segment Sharing**
```kotlin
// HLS segments cached with path preservation:
// master.m3u8 -> Cache Key: "QmVideo123/master.m3u8"
// segment0.ts -> Cache Key: "QmVideo123/segment0.ts"
// All players access same cached segments
```

### Cache Sharing Flow

#### MediaItemView → TweetDetailView
```kotlin
// 1. MediaItemView plays video and caches segments
VideoPreview(videoMid = "QmVideo123") // Downloads and caches

// 2. User navigates to TweetDetailView
TweetDetailBody() // Creates independent player

// 3. TweetDetailView VideoPreview uses same cache
VideoPreview(videoMid = "QmVideo123") // Reads from cache instantly!
```

#### Cache Hit Scenarios
- **Same video, different contexts**: MediaItemView → TweetDetailView → FullScreen
- **Same video, different nodes**: Node A → Node B (same media ID)
- **HLS segments**: Master playlist → Individual segments
- **Progressive videos**: Complete file cached and shared

### Cache Statistics
```kotlin
// In VideoManager.kt
fun getCacheStats(context: Context): String {
    val cache = getCache(context)
    val cacheSize = cache.cacheSpace
    val maxCacheSize = CACHE_SIZE_BYTES
    val usedPercentage = (cacheSize * 100 / maxCacheSize).toInt()
    return "Video Cache: ${cacheSize / (1024 * 1024)}MB / ${maxCacheSize / (1024 * 1024)}MB ($usedPercentage%)"
}
```

## 9. Memory Management

### Player Limits
- **Maximum Players**: 50 concurrent ExoPlayer instances
- **Memory Threshold**: 1GB with 60% cleanup ratio
- **Automatic Cleanup**: 15-second monitoring cycles

### Cleanup Strategy
```kotlin
// In VideoManager.kt
fun cleanupInactivePlayers() {
    val inactivePlayers = videoPlayers.keys.filter { videoMid ->
        !activeVideos.containsKey(videoMid) && !visibleVideos.contains(videoMid)
    }
    
    inactivePlayers.forEach { videoMid ->
        videoPlayers[videoMid]?.release()
        videoPlayers.remove(videoMid)
    }
}
```

## 10. Usage Examples

### MediaItemView → FullScreen Flow
```kotlin
// 1. User taps video in feed
MediaItemView(
    videoMid = videoMid,
    callback = { goto(index) }  // Triggers fullscreen
)

// 2. Navigate to MediaBrowser
val existingPlayer = VideoManager.transferToFullScreen(videoMid)

// 3. FullScreenVideoPlayer uses existing player
FullScreenVideoPlayer(
    existingPlayer = existingPlayer,
    onClose = {
        VideoManager.returnFromFullScreen(videoMid)
    }
)
```

### TweetDetailView Independent Flow
```kotlin
// 1. User navigates to tweet detail
TweetDetailBody(
    viewModel = viewModel,
    gridColumns = 1
)

// 2. MediaGrid creates independent videos
MediaItemView(
    useIndependentVideoMute = true  // Independent mute state
)

// 3. VideoPreview creates independent player
VideoPreview(
    useIndependentMuteState = true
)
```

## 11. Best Practices

### For Shared Players (MediaItemView ↔ FullScreen)
1. **Always use VideoManager**: Leverage centralized player management
2. **Preserve state**: Use `transferToFullScreen()` for seamless transitions
3. **Handle mute state**: Restore original mute state on exit
4. **Clean up properly**: Return player with `returnFromFullScreen()`

### For Independent Players (TweetDetailView)
1. **Use independent mute**: Set `useIndependentMuteState = true`
2. **Isolate behavior**: Don't interfere with feed video state
3. **Separate controls**: Independent playback control per context
4. **Memory awareness**: Independent players still managed by VideoManager

### General Guidelines
1. **Context awareness**: Choose appropriate pattern based on use case
2. **Memory management**: Let VideoManager handle player lifecycle
3. **Error handling**: Use retry mechanisms for network issues
4. **State preservation**: Maintain playback position and state appropriately

## 12. Troubleshooting

### Common Issues

#### Shared Player Issues
- **Position not preserved**: Check if `transferToFullScreen()` is called
- **Mute state conflicts**: Verify mute state restoration in `returnFromFullScreen()`
- **Player not transferring**: Ensure `videoMid` is consistent across components

#### Independent Player Issues
- **Mute state not independent**: Check `useIndependentMuteState` parameter
- **Feed interference**: Verify independent mute state is properly isolated
- **Memory leaks**: Ensure VideoManager cleanup is working

### Debug Logging
```kotlin
// Transfer logging
Timber.tag("transferToFullScreen").d("Transferring player for $videoMid to full-screen")
Timber.tag("returnFromFullScreen").d("Returning player for $videoMid from full-screen")

// Player creation logging
Timber.tag("VideoManager").d("Creating new player for video: $videoMid")
Timber.tag("VideoManager").d("Reusing existing player for video: $videoMid")
```

## 13. Performance Impact

### Shared Players Benefits
- **Memory efficiency**: Reuses existing player instances
- **Smooth transitions**: No interruption during fullscreen
- **Better UX**: Seamless video experience

### Independent Players Benefits
- **Isolated control**: No interference between contexts
- **Flexible behavior**: Different mute/playback behavior per context
- **Better UX**: Context-appropriate video behavior

## Conclusion

The dual ExoPlayer architecture provides optimal performance and user experience by:

- **Sharing players** for seamless feed-to-fullscreen transitions
- **Independent players** for isolated detail view control
- **Context-aware behavior** for appropriate video handling
- **Efficient memory management** through VideoManager
- **Robust error handling** with retry mechanisms

This architecture ensures the best of both worlds: smooth transitions where needed and independent control where appropriate.
