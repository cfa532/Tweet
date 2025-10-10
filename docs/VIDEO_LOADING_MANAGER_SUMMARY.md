# VideoLoadingManager Summary

## Overview

The `VideoLoadingManager` is a new intelligent video loading system that optimizes video performance in tweet lists by:

1. **Stopping videos that are scrolled past** - Prevents unnecessary loading and memory usage
2. **Preloading videos from upcoming tweets** - Ensures smooth playback when users scroll to videos
3. **Smart visibility tracking** - Only loads videos that are currently visible or about to become visible

## Key Features

### 1. **Intelligent Video Loading Control**
- **Visibility-based loading**: Videos only load when they become visible
- **Automatic stopping**: Videos that are scrolled past are automatically stopped
- **Preload ahead**: Videos from the next 3 tweets are preloaded in the background

### 2. **Smart Preloading Strategy**
- **Configurable preload count**: Currently set to preload videos from 3 upcoming tweets
- **Debounced preloading**: 500ms delay to avoid excessive loading during rapid scrolling
- **Memory-aware**: Integrates with existing VideoManager memory management

### 3. **Performance Optimizations**
- **Reduced memory usage**: Only keeps necessary videos in memory
- **Better battery life**: Stops unnecessary video loading
- **Smoother scrolling**: Prevents video loading from blocking scroll performance

## Implementation Details

### VideoLoadingManager.kt
```kotlin
object VideoLoadingManager {
    // Track visible and preloading videos
    private val visibleVideos = mutableSetOf<MimeiId>()
    private val preloadingVideos = mutableSetOf<MimeiId>()
    
    // Configuration
    private const val PRELOAD_AHEAD_COUNT = 3
    private const val PRELOAD_DELAY_MS = 500L
}
```

### Key Methods

#### `markVideoVisible(videoMid: MimeiId)`
- Marks a video as currently visible
- Allows the video to continue loading and playing

#### `markVideoNotVisible(videoMid: MimeiId)`
- Marks a video as no longer visible
- Automatically stops loading the video to save resources

#### `preloadUpcomingVideos(context, currentTweetIndex, tweets, baseUrl)`
- Preloads videos from the next 3 tweets
- Uses debounced loading to avoid excessive network requests
- Integrates with VideoManager for consistent caching

### Composable Hooks

#### `rememberVideoLoadingManager(videoMid, isVisible, onVisibilityChanged)`
- Manages individual video visibility
- Automatically calls `markVideoVisible`/`markVideoNotVisible`
- Provides visibility change callbacks

#### `rememberTweetVideoPreloader(tweets, currentVisibleIndex, baseUrl)`
- Manages preloading for entire tweet lists
- Automatically preloads videos from upcoming tweets
- Handles cleanup when component is disposed

## Integration Points

### 1. **VideoPreview Component**
```kotlin
// Use VideoLoadingManager to track visibility and manage loading
videoMid?.let { mid ->
    rememberVideoLoadingManager(
        videoMid = mid,
        isVisible = isVideoVisible
    )
}
```

### 2. **TweetListView Component**
```kotlin
// Use VideoLoadingManager to preload videos from upcoming tweets
val currentVisibleIndex = listState.firstVisibleItemIndex
rememberTweetVideoPreloader(
    tweets = tweets,
    currentVisibleIndex = currentVisibleIndex,
    baseUrl = baseUrl
)
```

## Benefits

### 1. **Performance Improvements**
- **Reduced memory usage**: Only visible videos are kept in memory
- **Faster scrolling**: No video loading blocking scroll performance
- **Better battery life**: Stops unnecessary video operations

### 2. **User Experience**
- **Smoother playback**: Videos are preloaded before becoming visible
- **No loading delays**: Preloaded videos start immediately
- **Consistent behavior**: Predictable video loading patterns

### 3. **Resource Management**
- **Network efficiency**: Only loads videos that will be viewed
- **Memory efficiency**: Automatically cleans up unused videos
- **CPU efficiency**: Stops unnecessary video processing

## Configuration Options

### Preload Settings
- `PRELOAD_AHEAD_COUNT = 3`: Number of upcoming tweets to preload videos from
- `PRELOAD_DELAY_MS = 500L`: Delay before starting preload to avoid excessive loading

### Memory Management
- Integrates with existing VideoManager memory thresholds
- Respects player count limits and cleanup strategies
- Automatic cleanup when memory pressure is high

## Usage Examples

### Basic Video Visibility Management
```kotlin
@Composable
fun VideoComponent(videoMid: MimeiId, isVisible: Boolean) {
    rememberVideoLoadingManager(
        videoMid = videoMid,
        isVisible = isVisible
    )
    
    // Video content here
}
```

### Tweet List Preloading
```kotlin
@Composable
fun TweetList(tweets: List<Tweet>) {
    val listState = rememberLazyListState()
    val currentVisibleIndex = listState.firstVisibleItemIndex
    
    rememberTweetVideoPreloader(
        tweets = tweets,
        currentVisibleIndex = currentVisibleIndex,
        baseUrl = tweets.getOrNull(currentVisibleIndex)?.author?.baseUrl ?: ""
    )
    
    // Tweet list content here
}
```

## Future Enhancements

### 1. **Adaptive Preloading**
- Adjust preload count based on network conditions
- Dynamic preload delay based on scroll speed
- Device-specific optimization

### 2. **Advanced Visibility Detection**
- Partial visibility support for large videos
- Viewport-based visibility calculations
- Scroll direction-aware preloading

### 3. **Analytics Integration**
- Track video loading performance
- Monitor user viewing patterns
- Optimize preloading based on usage data

## Testing Recommendations

1. **Scroll Performance**: Test scrolling through many tweets with videos
2. **Memory Usage**: Monitor memory consumption during extended scrolling
3. **Network Efficiency**: Verify preloading doesn't cause excessive network requests
4. **Battery Impact**: Test battery usage during video-heavy scrolling
5. **Edge Cases**: Test with slow network, rapid scrolling, and device rotation
