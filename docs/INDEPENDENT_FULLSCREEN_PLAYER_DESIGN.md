# Independent Fullscreen Player Design

## Overview
The fullscreen video player has been remodeled as an independent singleton that automatically plays the next video in the tweet list when the current video finishes. This system provides seamless video-to-video transitions and enhanced user experience.

## Current Architecture

### 1. Singleton Fullscreen Player Manager
The `FullScreenPlayerManager` singleton:
- Manages a single ExoPlayer instance dedicated to fullscreen playback
- Maintains the current tweet list context from navigation
- Handles automatic progression to the next video
- Provides seamless transitions between videos
- Supports looping back to the beginning when reaching the end of the list

### 2. Tweet List Context
The fullscreen player automatically determines:
- Current tweet list based on navigation context (TweetFeed, Bookmarks, Favorites, UserProfile)
- Current video index within that list
- Proper handling of retweets (uses retweet's position in current context, not original tweet's context)

### 3. Automatic Video Progression
When a video finishes:
- Automatically load and play the next video in the list
- Update the UI to show the new video's metadata
- Loop back to the beginning when reaching the end of the list
- Handle retweet navigation correctly

## Current Implementation

### FullScreenPlayerManager Features
```kotlin
object FullScreenPlayerManager {
    // Core functionality
    fun initialize(context: Context)
    fun setTweetList(tweets: List<Tweet>, startIndex: Int)
    fun playNextVideo() // Loops to beginning when reaching end
    fun playPreviousVideo() // Loops to end when reaching beginning
    
    // State management
    fun getCurrentPlayer(): ExoPlayer?
    fun getCurrentTweet(): Tweet?
    fun getCurrentIndex(): Int
    fun getTotalVideos(): Int
    fun cleanup()
    
    // Callbacks
    fun setOnVideoChanged(callback: (Tweet, Int) -> Unit)
    fun setOnPlayerStateChanged(callback: (PlayerState) -> Unit)
}
```

### IndependentFullScreenPlayer Features
- **Gesture Support**: 
  - Swipe up for next video (newer videos)
  - Small drag down for previous video (older videos)  
  - Large drag down to exit player
  - Visual feedback with video scaling and offset during gestures
- **UI Elements**:
  - Video counter (e.g., "2 of 5")
  - Tweet content and author information
  - Tap to toggle controls
  - Immersive mode support
- **Automatic Player Updates**: Reactive ExoPlayer updates when FullScreenPlayerManager creates new players

### Context-Aware Tweet Fetching
The system automatically determines the tweet list based on navigation context:
- **TweetFeed**: Uses `TweetFeedViewModel.tweets.value`
- **Bookmarks**: Fetches from `HproseInstance.getUserTweetsByType(UserContentType.BOOKMARKS)`
- **Favorites**: Fetches from `HproseInstance.getUserTweetsByType(UserContentType.FAVORITES)`
- **UserProfile**: Fetches from `HproseInstance.getTweetsByUser()`
- **Retweets**: Special handling to use retweet's position in current context

## Current UI/UX Features

### Video Controls
- Automatic play/pause
- Next/Previous video navigation
- Video position indicator (e.g., "2 of 5")
- Close button

### Video Information Display
- Tweet content
- Author information (@username)
- Video counter
- Semi-transparent overlay

### Gesture Support
- **Swipe up**: Next video (newer videos in feed)
- **Small drag down**: Previous video (older videos in feed)
- **Large drag down**: Exit player
- **Tap**: Toggle controls visibility
- **Visual feedback**: Video scales and moves during drag gestures

## Current Benefits
1. **Automatic Progression**: Videos automatically advance when they finish
2. **Consistent Experience**: Single player instance ensures smooth transitions
3. **Better Performance**: No player conflicts or memory issues
4. **Enhanced UX**: Users can binge-watch videos in tweet lists
5. **Simplified Architecture**: Centralized video management
6. **Context Awareness**: Automatically determines correct tweet list
7. **Retweet Support**: Proper handling of retweet navigation
8. **Gesture Navigation**: Intuitive swipe-based video navigation

## Current Integration Points
- **MediaBrowser**: Uses `IndependentFullScreenPlayer` with context-aware tweet list fetching
- **ChatScreen**: Continues to use original `FullScreenVideoPlayer` (unchanged)
- **MediaItemView**: Navigates to `MediaBrowser` which uses the new system

## Technical Implementation Details
- **Memory Management**: Proper cleanup when player is disposed
- **Player State**: Reactive ExoPlayer updates using `mutableStateOf`
- **Gesture Detection**: Uses `detectDragGestures` with visual feedback
- **Context Detection**: Analyzes navigation routes to determine tweet source
- **Video Filtering**: Only includes tweets with video attachments
- **Looping Behavior**: Automatically loops to beginning when reaching end of list
