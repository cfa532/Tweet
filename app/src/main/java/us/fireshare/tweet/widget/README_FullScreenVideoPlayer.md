# FullScreenVideoPlayer Documentation

## Overview

The `FullScreenVideoPlayer` component provides an immersive full-screen video viewing experience with auto-replay functionality, independent mute state management, and efficient player reuse from MediaPreview.

## Key Features

### 1. **Auto-Replay Functionality**
- **Automatic restart**: Videos automatically restart when they end in full screen mode
- **Configurable**: Can be enabled/disabled via the `autoReplay` parameter
- **Seamless experience**: No interruption in playback during replay

### 2. **Independent Mute State Management**
- **Full screen unmuted**: Videos are always unmuted (volume = 1f) in full screen mode
- **State preservation**: Original mute state is stored when entering full screen
- **State restoration**: Original mute state is restored when exiting full screen
- **No interference**: Full screen mute state doesn't affect MediaPreview mute state

### 3. **Efficient Player Reuse**
- **Shared instances**: Reuses the same ExoPlayer instance from MediaPreview
- **State preservation**: Maintains current playback position and state
- **Memory optimization**: Prevents creation of duplicate player instances
- **Smooth transitions**: No interruption when switching between preview and full screen

## Component Parameters

```kotlin
@Composable
fun FullScreenVideoPlayer(
    videoMid: MimeiId,                    // Unique video identifier
    videoUrl: String,                     // Video URL
    onClose: () -> Unit,                  // Callback when user exits full screen
    autoPlay: Boolean = true,             // Auto-start playback when entering full screen (default)
    enableImmersiveMode: Boolean = true,  // Enable immersive mode (hide system bars)
    autoReplay: Boolean = true,           // Auto-replay when video ends
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // Horizontal swipe navigation
)
```

## Usage Examples

### Basic Usage
```kotlin
FullScreenVideoPlayer(
    videoMid = videoMid,
    videoUrl = videoUrl,
    onClose = { /* Handle exit */ }
)
```

### With Custom Settings
```kotlin
FullScreenVideoPlayer(
    videoMid = videoMid,
    videoUrl = videoUrl,
    onClose = { /* Handle exit */ },
    autoReplay = true,
    enableImmersiveMode = true,
    onHorizontalSwipe = { direction ->
        // Handle horizontal swipe navigation
        when (direction) {
            -1 -> navigateToPrevious()
            1 -> navigateToNext()
        }
    }
)
```

## Mute State Handling

### Entering Full Screen
1. **Store original state**: Captures the current volume level (0f = muted, 1f = unmuted)
2. **Set to unmuted**: Forces volume to 1f for full screen experience
3. **Preserve playback**: Maintains current playback position and state

### During Full Screen
- **Always unmuted**: Volume remains at 1f regardless of original state
- **Independent control**: User can adjust volume using system controls
- **No interference**: Changes don't affect the original MediaPreview state

### Exiting Full Screen
1. **Restore original state**: Sets volume back to original level (0f or 1f)
2. **Preserve position**: Maintains current playback position
3. **Resume playback**: Continues from where it left off in MediaPreview

## Auto-Replay Implementation

### When Auto-Replay is Enabled
```kotlin
override fun onPlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
        androidx.media3.common.Player.STATE_ENDED -> {
            if (autoReplay) {
                exoPlayer.seekTo(0)
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
        }
    }
}
```

### When Auto-Replay is Disabled
- **Sequential playback**: Notifies VideoManager for playlist handling
- **Manual control**: User must manually restart the video
- **Integration**: Works with VideoManager's sequential playback system

## Player Reuse Strategy

### Getting Player Instance
```kotlin
val exoPlayer = remember(videoMid) {
    // Get existing player from VideoManager
    val existingPlayer = VideoManager.getVideoPlayer(context, videoMid, videoUrl)
    
    // Store original state
    originalMuteState = existingPlayer.volume == 0f
    wasPlayingBefore = existingPlayer.playWhenReady
    
    existingPlayer
}
```

### State Preservation
- **Playback position**: Maintains current time position
- **Playback state**: Preserves play/pause state
- **Volume state**: Stores original mute state for restoration
- **Buffer state**: Keeps existing buffered content

## Integration with VideoManager

### Player Retrieval
- **State preservation**: Uses `VideoManager.getVideoPlayer()` to get existing player
- **Memory management**: Leverages VideoManager's memory optimization
- **Error handling**: Benefits from VideoManager's error recovery

### State Management
- **Active tracking**: VideoManager tracks active video instances
- **Memory cleanup**: Automatic cleanup of unused players
- **Sequential playback**: Integration with playlist functionality

## User Interaction

### Gesture Controls
- **Tap**: Toggle control visibility
- **Vertical drag**: Swipe down to exit full screen
- **Horizontal drag**: Navigate between videos (if supported)

### Visual Feedback
- **Drag animation**: Smooth visual effects during drag gestures
- **Control fade**: Auto-hide controls after 1 second
- **Exit animation**: Smooth transition when exiting full screen

## Error Handling

### Player Errors
- **Graceful degradation**: Logs errors without crashing
- **Fallback support**: Leverages VideoManager's fallback mechanisms
- **User feedback**: Maintains responsive UI during errors

### Network Issues
- **Buffering indicators**: Shows loading state during network delays
- **Error recovery**: Automatic retry through VideoManager
- **Offline support**: Works with cached content

## Performance Considerations

### Memory Management
- **Player reuse**: Prevents duplicate player instances
- **State preservation**: Avoids unnecessary player resets
- **Automatic cleanup**: VideoManager handles memory optimization

### Battery Optimization
- **Efficient playback**: Reuses existing buffered content
- **State tracking**: Minimal overhead for state management
- **Resource sharing**: Shared player instances reduce resource usage

## Best Practices

### Implementation
1. **Always use VideoManager**: Leverage centralized player management
2. **Preserve state**: Use `getVideoPlayer()` for full screen
3. **Handle mute state**: Always restore original mute state on exit
4. **Enable auto-replay**: Provide better user experience in full screen

### User Experience
1. **Smooth transitions**: Ensure no interruption during mode switches
2. **Consistent behavior**: Maintain predictable mute state behavior
3. **Responsive controls**: Provide immediate feedback to user actions
4. **Accessibility**: Support system volume controls and accessibility features

## Troubleshooting

### Common Issues
1. **Volume not restored**: Check if `originalMuteState` is properly captured
2. **Auto-replay not working**: Verify `autoReplay` parameter is set to `true`
3. **Player not reusing**: Ensure `VideoManager.getVideoPlayer()` is used
4. **State loss**: Check if player state is being reset unnecessarily
5. **Video stops after entering full screen**: This was fixed by using `LaunchedEffect(Unit)` instead of `LaunchedEffect(exoPlayer)` to prevent multiple triggers
6. **Auto-play not working**: This was fixed by overriding VideoManager's `resetPlayerState()` which was setting `playWhenReady = false` for reused players, plus added aggressive playback maintenance with periodic checks and `onIsPlayingChanged` listener

### Debug Logging
- **State changes**: Logs playback state transitions
- **Mute state**: Logs mute state restoration
- **Player reuse**: Logs when existing players are reused
- **Error handling**: Logs player errors and recovery attempts

## Future Enhancements

### Potential Improvements
1. **Custom controls**: Add custom video controls overlay
2. **Picture-in-picture**: Support for PiP mode
3. **Quality selection**: Dynamic quality switching
4. **Subtitle support**: Display video subtitles
5. **Playback speed**: Variable playback speed controls 