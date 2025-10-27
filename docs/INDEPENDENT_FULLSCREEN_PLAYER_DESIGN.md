# Independent Fullscreen Player Design

## Overview
Remodel the fullscreen video player to be an independent singleton that automatically plays the next video in the tweet list when the current video finishes.

## Architecture Changes

### 1. Singleton Fullscreen Player Manager
Create a new `FullScreenPlayerManager` singleton that:
- Manages a single ExoPlayer instance dedicated to fullscreen playback
- Maintains the current tweet list context
- Handles automatic progression to the next video
- Provides seamless transitions between videos

### 2. Tweet List Context
The fullscreen player needs to know:
- Current tweet list (from which screen it was opened)
- Current video index within that list
- Navigation context to return to the correct screen

### 3. Automatic Video Progression
When a video finishes:
- Automatically load and play the next video in the list
- Update the UI to show the new video's metadata
- Handle end-of-list scenarios (loop back to start or close player)

## Implementation Plan

### Phase 1: Create FullScreenPlayerManager
```kotlin
object FullScreenPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentTweetList: List<Tweet>? = null
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((Tweet, Int) -> Unit)? = null
    
    fun initialize(context: Context)
    fun setTweetList(tweets: List<Tweet>, startIndex: Int)
    fun playNextVideo()
    fun playPreviousVideo()
    fun getCurrentPlayer(): ExoPlayer?
    fun cleanup()
}
```

### Phase 2: Update FullScreenVideoPlayer Composable
- Remove dependency on existing player instances
- Use the singleton FullScreenPlayerManager
- Add UI for next/previous video navigation
- Show current video metadata (tweet content, author, etc.)

### Phase 3: Integration Points
- Update MediaBrowser to use the new system
- Update MediaItemView to launch the new fullscreen player
- Ensure proper cleanup when closing fullscreen

## UI/UX Enhancements

### Video Controls
- Play/Pause button
- Next/Previous video buttons
- Progress bar for current video
- Close button

### Video Information Display
- Tweet content
- Author information
- Video position in list (e.g., "2 of 5")
- Timestamp

### Gesture Support
- Swipe left/right for next/previous video
- Tap to show/hide controls
- Double-tap to play/pause

## Benefits
1. **Consistent Experience**: Single player instance ensures consistent behavior
2. **Automatic Progression**: Seamless video-to-video transitions
3. **Better Performance**: No player conflicts or memory issues
4. **Enhanced UX**: Users can binge-watch videos in a tweet list
5. **Simplified Architecture**: Centralized video management

## Migration Strategy
1. Implement FullScreenPlayerManager alongside existing system
2. Update FullScreenVideoPlayer to use the new manager
3. Test thoroughly with different tweet lists
4. Remove old player transfer logic once stable
5. Update all fullscreen entry points

## Technical Considerations
- Memory management for the singleton player
- Proper cleanup when app goes to background
- Handling of different video formats (HLS vs Progressive)
- Cache management for smooth transitions
- Error handling and recovery
