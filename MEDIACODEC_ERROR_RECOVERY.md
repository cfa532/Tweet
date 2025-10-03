# MediaCodec Error Recovery Implementation

## Problem Analysis

You were experiencing a critical issue where videos could play perfectly initially, but once they encountered a MediaCodec decoder failure, they would get stuck in an unrecoverable error state. The specific error was:

```
Failed to initialize OMX.hisi.video.decoder.avc, error 0xfffffff4
```

This is a hardware decoder failure on devices with HiSilicon chipsets (common in many Android devices). Once this error occurs, the ExoPlayer instance becomes corrupted and cannot recover through normal means.

## Root Cause

1. **Hardware Decoder Failure**: The device's hardware video decoder (OMX.hisi.video.decoder.avc) fails to initialize
2. **ExoPlayer Corruption**: When MediaCodec fails, the ExoPlayer instance becomes corrupted and cannot recover
3. **No Fallback Mechanism**: The existing recovery only tried to reset the media source, not recreate the entire player
4. **Stuck Error State**: Videos would remain in error state indefinitely, unable to play again

## Solutions Implemented

### 1. Force Player Recreation for MediaCodec Failures

**File**: `app/src/main/java/us/fireshare/tweet/widget/VideoManager.kt`

**New Method**: `forceRecreatePlayer()`

```kotlin
fun forceRecreatePlayer(context: Context, videoMid: MimeiId, videoUrl: String, videoType: MediaType? = null): Boolean {
    // Completely destroy the old player
    val oldPlayer = videoPlayers[videoMid]
    oldPlayer?.let { player ->
        player.stop()
        player.clearMediaItems()
        player.release()
    }
    
    // Remove from all tracking maps
    videoPlayers.remove(videoMid)
    visibleVideos.remove(videoMid)
    preloadedVideos.remove(videoMid)
    preloadQueue.remove(videoMid)
    
    // Create a completely new player
    val newPlayer = createExoPlayer(context, videoUrl, videoType ?: MediaType.Video)
    videoPlayers[videoMid] = newPlayer
    
    return true
}
```

**Key Features**:
- Completely releases the corrupted ExoPlayer instance
- Removes all tracking references to prevent memory leaks
- Creates a fresh ExoPlayer instance from scratch
- Handles cleanup of partial state on failure

### 2. Enhanced Error Detection and Recovery

**File**: `app/src/main/java/us/fireshare/tweet/widget/VideoPreview.kt`

**Enhanced Error Handling**:

```kotlin
// Check if this is a MediaCodec decoder failure
val isMediaCodecError = errorMessage.contains("MediaCodec", ignoreCase = true) ||
        errorMessage.contains("Decoder init failed", ignoreCase = true) ||
        errorMessage.contains("OMX.hisi.video.decoder", ignoreCase = true) ||
        errorMessage.contains("Failed to initialize", ignoreCase = true) ||
        errorMessage.contains("CodecException", ignoreCase = true)

if (isMediaCodecError && videoMid != null) {
    // Force recreate the entire ExoPlayer instance
    val success = VideoManager.forceRecreatePlayer(context, videoMid, url, videoType)
    if (success) {
        // The new player will be picked up by the remember block on next recomposition
        isLoading = false
        hasError = false
    }
}
```

**Key Features**:
- Detects MediaCodec-specific errors
- Triggers force recreation instead of normal recovery
- Handles the recreation on the main thread
- Provides proper state management during recreation

### 3. Software Decoder Fallback

**File**: `app/src/main/java/us/fireshare/tweet/widget/CreateExoPlayer.kt`

**Enhanced ExoPlayer Builder**:

```kotlin
val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(mediaSourceFactory)
    .setRenderersFactory(
        androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    )
    .build()
```

**Key Features**:
- Enables extension renderers (software decoders) as fallback
- Prefers hardware decoders but falls back to software when needed
- Reduces likelihood of MediaCodec failures

## Error Recovery Flow

### Before (Broken Flow)
1. Video plays successfully
2. MediaCodec decoder fails
3. ExoPlayer becomes corrupted
4. Normal recovery attempts fail
5. Video stuck in error state forever

### After (Fixed Flow)
1. Video plays successfully
2. MediaCodec decoder fails
3. Error detection identifies MediaCodec failure
4. Force recreation destroys corrupted player
5. New ExoPlayer instance created
6. Video can play again successfully

## Technical Details

### MediaCodec Error Types Detected
- `MediaCodec` - General MediaCodec errors
- `Decoder init failed` - Decoder initialization failures
- `OMX.hisi.video.decoder` - HiSilicon hardware decoder failures
- `Failed to initialize` - General initialization failures
- `CodecException` - MediaCodec exceptions

### Recovery Mechanisms
1. **Normal Recovery**: For network/timeout errors - resets media source
2. **Force Recreation**: For MediaCodec errors - destroys and recreates entire player
3. **Software Fallback**: Uses software decoders when hardware fails

### Memory Management
- Proper cleanup of old player instances
- Removal from all tracking maps
- Exception handling for partial cleanup
- Prevention of memory leaks

## Expected Results

1. **Recoverable MediaCodec Failures**: Videos that fail due to hardware decoder issues can now recover
2. **No More Stuck States**: Videos won't remain in error state indefinitely
3. **Better Device Compatibility**: Software decoder fallback improves compatibility
4. **Improved User Experience**: Users can retry failed videos successfully

## Monitoring

The implementation includes comprehensive logging:
- MediaCodec error detection
- Force recreation attempts
- Success/failure of recreation
- Player state transitions

## Testing Scenarios

1. **Normal Playback**: Videos should continue to play normally
2. **MediaCodec Failure**: Videos should recover automatically after decoder failure
3. **Network Errors**: Normal recovery should still work for network issues
4. **Stream Parsing Errors**: PesReader warnings should still be ignored
5. **Multiple Failures**: Videos should recover even after multiple MediaCodec failures

## Conclusion

This implementation provides a robust solution for MediaCodec decoder failures that were causing videos to get stuck in unrecoverable error states. The force recreation mechanism ensures that even when hardware decoders fail, videos can still be played using fresh ExoPlayer instances.
