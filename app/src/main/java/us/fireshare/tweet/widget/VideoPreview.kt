package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.utils.ErrorMessageUtils
import us.fireshare.tweet.widget.Gadget.calculateVisibilityRatio
import us.fireshare.tweet.widget.Gadget.isElementVisible

/**
 * @param index: when there are multiple videos in a grid, the first one is played automatically.
 * @param inPreviewGrid: If the video is previewed in a Grid as part of tweet item in a list.
 *                       The aspect ratio shall be 1:1, otherwise use the video's real aspectRatio.
 * @param callback: callback function to be performed when video is closed.
 * **/
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    url: String?,
    modifier: Modifier,
    index: Int,
    autoPlay: Boolean = false,
    inPreviewGrid: Boolean = true,
    callback: (Int) -> Unit,
    videoMid: MimeiId? = null,
    videoType: MediaType? = null,
    onLoadComplete: (() -> Unit)? = null,
    onVideoCompleted: (() -> Unit)? = null,
    useIndependentMuteState: Boolean = false, // For TweetDetailView - independent of global mute state
    enableTapToShowControls: Boolean = false, // New parameter for tap-to-show controls
    playbackTweetId: MimeiId? = null, // Container tweet id for coordinator tracking
    containerTopY: Float? = null
) {
    val context = LocalContext.current

    // Use completely stable state that doesn't change during recompositions
    var isVideoVisible by remember(videoMid) { mutableStateOf(false) }
    
    // PERF FIX: Throttle visibility updates to reduce expensive calculations during scrolling
    var lastVisibilityUpdate by remember(videoMid) { mutableLongStateOf(0L) }
    val visibilityUpdateThrottleMs = 100L // Only update every 100ms during scrolling
    
    // If using independent mute state (TweetDetailView/FullScreen), start unmuted and don't sync with global state
    // Otherwise (MediaItem in feeds), use global mute state
    var isMuted by remember(videoMid) { 
        mutableStateOf(if (useIndependentMuteState) false else preferenceHelper.getSpeakerMute()) 
    }
    var isLoading by remember(videoMid) {
        mutableStateOf(
            videoMid?.let { mid ->
                val player = VideoManager.getCachedVideoPlayer(mid)
                if (player != null) {
                    // Player exists - only hide spinner if actually ready to play
                    player.playbackState != androidx.media3.common.Player.STATE_READY
                } else {
                    // No player yet - show loading
                    true
                }
            } ?: true
        )
    }
    var hasError by remember(videoMid) { mutableStateOf(false) }
    var showTimeLabel by remember(videoMid) { mutableStateOf(false) }
    var remainingTime by remember(videoMid) { mutableLongStateOf(0L) }
    var retryCount by remember(videoMid) { mutableIntStateOf(0) }
    var showControls by remember(videoMid) { mutableStateOf(false) } // Simple state for tap-to-show controls
    val maxRetries = 3
    val coordinator = LocalVideoCoordinator.current
    val shouldUseCoordinator = playbackTweetId != null && videoMid != null
    var coordinatorWantsToPlay by remember(videoMid, playbackTweetId) { mutableStateOf(false) }
    val shouldPlay = if (shouldUseCoordinator) coordinatorWantsToPlay else autoPlay
    LaunchedEffect(videoMid, playbackTweetId, coordinator) {
        if (!shouldUseCoordinator) {
            coordinatorWantsToPlay = false
            return@LaunchedEffect
        }
        // Check current primary state immediately instead of relying on SharedFlow replay.
        // The replay buffer (size 1) can be overwritten by unrelated commands (e.g.,
        // ShouldStopVideo for another video), causing this video to miss its play command.
        coordinatorWantsToPlay = coordinator.shouldAutoPlay(videoMid!!, playbackTweetId!!)
        coordinator.playbackCommands.collect { command ->
            when (command) {
                is VideoPlaybackCommand.ShouldPlayVideo -> {
                    coordinatorWantsToPlay = command.videoMid == videoMid && command.tweetId == playbackTweetId
                }
                is VideoPlaybackCommand.ShouldPauseVideo -> {
                    if (command.videoMid == videoMid) {
                        coordinatorWantsToPlay = false
                    }
                }
                is VideoPlaybackCommand.ShouldStopVideo -> {
                    if (command.videoMid == videoMid) {
                        coordinatorWantsToPlay = false
                    }
                }
                VideoPlaybackCommand.ShouldStopAllVideos -> {
                    coordinatorWantsToPlay = false
                }
            }
        }
    }
    
    // Use lifecycle-aware coroutine scope for retry operations to prevent memory leaks
    val retryScope = rememberCoroutineScope()

    // Use VideoLoadingManager to track visibility and manage loading.
    // Use effectivelyVisible (not raw isVideoVisible) so the coordinator's primary video
    // won't be stopped by markVideoNotVisible() while still selected for playback.
    val effectivelyVisible = if (shouldUseCoordinator) (isVideoVisible || shouldPlay) else isVideoVisible
    videoMid?.let { mid ->
        rememberVideoLoadingManager(
            videoMid = mid,
            isVisible = effectivelyVisible
        )
    }

    // Use videoMid as the only key to prevent ExoPlayer recreation
    val exoPlayer = remember(videoMid) {
        val player = if (videoMid != null && url != null) {
            VideoManager.getVideoPlayer(context, videoMid, url, videoType)
        } else if (url != null) {
            createExoPlayer(context, url, videoType ?: MediaType.Video)
        } else {
            // Fallback to an empty player if url is null
            createExoPlayer(context, "", videoType ?: MediaType.Video)
        }

        // Explicitly disable repeat mode to prevent auto-replay
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF

        player
    }

    // Video preloading is handled by parent components (ChatScreen, MediaItemView, etc.)
    // This prevents conflicts and race conditions

    /**
     * Stop playing when screen is locked or closed. Resume play when unlocked.
     * Keep ExoPlayer in memory for better user experience.
     * */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        // Do not play video by default.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Pause video playback but keep player in memory
                    exoPlayer.playWhenReady = false
                }

                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    // Resume video playback if coordinator/autoPlay wants it
                    val effectivelyVisible = if (shouldUseCoordinator) (isVideoVisible || shouldPlay) else isVideoVisible
                    if (effectivelyVisible && shouldPlay) {
                        exoPlayer.playWhenReady = true
                    }
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Mark video as inactive in VideoManager instead of releasing
            videoMid?.let { mid ->
                VideoManager.markVideoInactive(mid)
            }
            // Report 0 visibility so the coordinator removes this video from its
            // visible list. Without this, the stale 100% entry persists after the
            // composable is recycled by LazyColumn and the coordinator never
            // switches to the correct primary video.
            if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                coordinator.updateVideoVisibility(
                    videoMid = videoMid,
                    tweetId = playbackTweetId,
                    visibilityRatio = 0f
                )
            }
        }
    }

    LaunchedEffect(isVideoVisible, shouldPlay) {
        if (effectivelyVisible) {
            // Mark video as active in VideoManager
            videoMid?.let { mid ->
                VideoManager.markVideoActive(mid)
            }

            exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF

            // Handle different player states properly to avoid stuck states
            when (exoPlayer.playbackState) {
                androidx.media3.common.Player.STATE_READY -> {
                    exoPlayer.playWhenReady = shouldPlay
                    isLoading = false
                    hasError = false
                }
                androidx.media3.common.Player.STATE_IDLE -> {
                    // Player is idle (e.g., stopped when scrolled off-screen).
                    // Re-prepare to resume buffering from disk cache.
                    isLoading = true
                    hasError = false
                    if (exoPlayer.mediaItemCount == 0) {
                        if (videoMid != null && url != null) {
                            VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                        }
                    } else {
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = shouldPlay
                    }
                }
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    exoPlayer.playWhenReady = shouldPlay
                    isLoading = true
                    hasError = false
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    hasError = false
                }
                else -> {
                    isLoading = true
                    hasError = false
                    if (videoMid != null && url != null) {
                        VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                    }
                }
            }
        } else {
            // Only pause if this is the only active instance of this video
            videoMid?.let { mid ->
                val activeCount = VideoManager.getVideoActiveCount(mid)
                val isInFullScreen = VideoManager.isVideoInFullScreen(mid)
                if (activeCount <= 1 && !isInFullScreen) {
                    exoPlayer.playWhenReady = false
                }
            } ?: run {
                exoPlayer.playWhenReady = false
            }
        }
    }

    // Note: Stream parsing error monitoring removed - errors are now ignored to keep playback continuous

    LaunchedEffect(isMuted) {
        try {
            exoPlayer.volume = if (isMuted) 0f else 1f
            // Only persist mute state to global preferences if NOT using independent mute state
            // TweetDetailView and FullScreen videos should not affect global mute state
            if (!useIndependentMuteState) {
                preferenceHelper.setSpeakerMute(isMuted)
            }
        } catch (e: Exception) {
            Timber.e("VideoPreview - Error setting volume: ${e.message}")
        }
    }

    // Watch for global mute state changes only when visible, at a relaxed cadence
    // Skip this synchronization if using independent mute state (TweetDetailView/FullScreen)
    LaunchedEffect(isVideoVisible, useIndependentMuteState) {
        if (!isVideoVisible || useIndependentMuteState) return@LaunchedEffect
        while (isVideoVisible) {
            val globalMuteState = preferenceHelper.getSpeakerMute()
            if (isMuted != globalMuteState) {
                isMuted = globalMuteState
            }
            delay(500) // Check every 500ms while visible
        }
    }

    // Show time label when video starts playing and auto-hide after 3 seconds
    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            showTimeLabel = true
            delay(5000)
            showTimeLabel = false
        }
    }
    
    // Auto-hide controls after 3 seconds when enabled
    LaunchedEffect(showControls) {
        if (showControls && enableTapToShowControls) {
            delay(2000)
            showControls = false
        }
    }

    // Update remaining time every second when time label is shown
    LaunchedEffect(showTimeLabel) {
        while (showTimeLabel) {
            remainingTime = exoPlayer.duration - exoPlayer.currentPosition
            delay(1000)
        }
    }

    // Create a single listener that will be properly managed
    val playerListener = remember {
        object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        isLoading = false
                        onLoadComplete?.invoke()
                    }

                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        // Show loading spinner when video is buffering data
                        isLoading = true
                    }

                    androidx.media3.common.Player.STATE_ENDED -> {
                        isLoading = false
                        // Rewind is handled by CreateExoPlayer listener, but we still need to handle callbacks
                        exoPlayer.playWhenReady = false
                        videoMid?.let { mid ->
                            VideoManager.onVideoCompleted(mid)
                        }
                    if (videoMid != null && playbackTweetId != null) {
                        coordinator.handleVideoFinished(videoMid, playbackTweetId)
                    }
                        // Call the completion callback for sequential playback
                        onVideoCompleted?.invoke()
                    }

                    androidx.media3.common.Player.STATE_IDLE -> {
                        isLoading = true
                        // If this video should be playing, re-prepare immediately.
                        // This handles external stops (e.g., markVideoNotVisible from
                        // another composable's disposal) that the LaunchedEffect can't
                        // detect because its keys (isVideoVisible, shouldPlay) didn't change.
                        val currentShouldPlay = if (shouldUseCoordinator) coordinatorWantsToPlay else autoPlay
                        if (currentShouldPlay && exoPlayer.mediaItemCount > 0) {
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.tag("VideoPreview").e("Video loading error for $videoMid: ${error.message}")
                
                // Check if this is a MediaCodec decoder failure
                val errorMessage = error.cause?.message ?: ""
                val isMediaCodecError = errorMessage.contains("MediaCodec", ignoreCase = true) ||
                        errorMessage.contains("Decoder init failed", ignoreCase = true) ||
                        errorMessage.contains("OMX.hisi.video.decoder", ignoreCase = true) ||
                        errorMessage.contains("OMX.", ignoreCase = true) ||
                        errorMessage.contains("Failed to initialize", ignoreCase = true) ||
                        errorMessage.contains("CodecException", ignoreCase = true) ||
                        errorMessage.contains("DecoderInitializationException", ignoreCase = true) ||
                        errorMessage.contains("MediaCodecRenderer", ignoreCase = true) ||
                        errorMessage.contains("error 0xfffffff4", ignoreCase = true) ||
                        errorMessage.contains("native_setup", ignoreCase = true)
                
                // Check if this is a stream parsing error that we should ignore
                val isStreamParsingError = errorMessage.contains("Unexpected start code", ignoreCase = true) ||
                        errorMessage.contains("PesReader", ignoreCase = true) ||
                        errorMessage.contains("start code prefix", ignoreCase = true)
                
                // Check if this is a recoverable error
                val isRecoverableError = errorMessage.contains("network", ignoreCase = true) ||
                        errorMessage.contains("timeout", ignoreCase = true) ||
                        errorMessage.contains("connection", ignoreCase = true) ||
                        errorMessage.contains("server", ignoreCase = true) ||
                        errorMessage.contains("400", ignoreCase = true) ||  // Bad Request
                        errorMessage.contains("401", ignoreCase = true) ||  // Unauthorized
                        errorMessage.contains("403", ignoreCase = true) ||  // Forbidden
                        errorMessage.contains("404", ignoreCase = true) ||  // Not Found
                        errorMessage.contains("408", ignoreCase = true) ||  // Request Timeout
                        errorMessage.contains("429", ignoreCase = true) ||  // Too Many Requests
                        errorMessage.contains("500", ignoreCase = true) ||
                        errorMessage.contains("502", ignoreCase = true) ||
                        errorMessage.contains("503", ignoreCase = true) ||
                        errorMessage.contains("504", ignoreCase = true) ||
                        errorMessage.contains("InvalidResponseCodeException", ignoreCase = true) ||
                        errorMessage.contains("HttpDataSource", ignoreCase = true)
                
                if (isStreamParsingError) {
                    // For stream parsing errors, just ignore and keep playing
                    // These are typically non-fatal warnings from PesReader about malformed start codes
                    Timber.tag("VideoPreview").d("Ignoring stream parsing error and continuing playback for video: $videoMid - ${error.message}")
                    Timber.tag("VideoPreview").d("Stream parsing errors are common with HLS and usually don't affect playback quality")
                    isLoading = false
                    hasError = false
                    // Don't increment retry count for parsing errors
                } else if (isMediaCodecError && videoMid != null && retryCount < maxRetries) {
                    // For MediaCodec failures, force recreate with software decoder
                    retryCount++
                    Timber.tag("VideoPreview").w("MediaCodec decoder failure detected (attempt $retryCount/$maxRetries), force recreating with software decoder for video: $videoMid")
                    isLoading = true
                    hasError = false
                    
                    // Force recreate with software decoder with delay to prevent rapid retries
                    // Use lifecycle-aware scope to prevent memory leaks if composable is disposed
                    retryScope.launch {
                        try {
                            // Add delay before retry to prevent rapid retry loops
                            delay(1000) // Wait 1 second before MediaCodec recovery attempt
                            
                            // Force recreate the entire player with software decoder
                            val recreateSuccess = if (videoMid != null && url != null) {
                                VideoManager.forceRecreatePlayer(context, videoMid, url, videoType)
                            } else {
                                false
                            }
                            
                            if (recreateSuccess) {
                                Timber.tag("VideoPreview").d("Player force recreated with software decoder for video: $videoMid")
                                isLoading = false
                                hasError = false
                            } else {
                                Timber.tag("VideoPreview").e("Failed to force recreate player for video: $videoMid")
                                isLoading = false
                                hasError = true
                            }
                        } catch (e: Exception) {
                            Timber.tag("VideoPreview").e("Exception during MediaCodec recovery for video: $videoMid - ${e.message}")
                            isLoading = false
                            hasError = true
                        }
                    }
                } else if (isMediaCodecError && videoMid != null) {
                    // MediaCodec error but exceeded retry limit
                    Timber.tag("VideoPreview").e("MediaCodec decoder failure exceeded retry limit ($maxRetries) for video: $videoMid")
                    Timber.tag("VideoPreview").e("This device may not support software decoders for this video format")
                    isLoading = false
                    hasError = true
                } else if (isRecoverableError && retryCount < maxRetries && videoMid != null) {
                    // Only retry for non-parsing, non-MediaCodec recoverable errors
                    retryCount++
                    Timber.tag("VideoPreview").d("Attempting automatic retry $retryCount/$maxRetries for video: $videoMid")
                    
                    // Keep loading state during retry
                    isLoading = true
                    hasError = false
                    
                    // Retry after a delay
                    // Use lifecycle-aware scope to prevent memory leaks if composable is disposed
                    retryScope.launch {
                        delay(1000) // Wait 1 second before retry
                        if (isLoading && !hasError && videoMid != null && url != null) {
                            VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                        }
                    }
                } else {
                    // For non-recoverable errors or after max retries, show error state
                    isLoading = false
                    hasError = true
                    val userFriendlyError = ErrorMessageUtils.getVideoErrorMessage(context, error)
                    Timber.tag("VideoPreview").e("Final error for video: $videoMid - $userFriendlyError (retries: $retryCount, recoverable: $isRecoverableError, mediaCodec: $isMediaCodecError)")
                }
            }
        }
    }

    // Add and remove listener properly
    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        
        // Sync isLoading with actual player state immediately in case we missed state changes
        // This handles race conditions where the player state changed before listener attachment
        when (exoPlayer.playbackState) {
            androidx.media3.common.Player.STATE_READY -> isLoading = false
            androidx.media3.common.Player.STATE_BUFFERING,
            androidx.media3.common.Player.STATE_IDLE -> isLoading = true
        }
        
        onDispose {
            exoPlayer.removeListener(playerListener)
        }
    }

    // When previewing a single video, limit its height to show more content.
    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant) // Material3 surface variant for loading background
            .onGloballyPositioned { layoutCoordinates ->
                // PERF FIX: Throttle visibility calculations during scrolling
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - lastVisibilityUpdate

                // Always update local visibility state for UI (lightweight check)
                val newVisibility = isElementVisible(layoutCoordinates)
                if (isVideoVisible != newVisibility) {
                    isVideoVisible = newVisibility
                }

                // Report this video's own visibility ratio to the coordinator
                if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                    if (timeSinceLastUpdate >= visibilityUpdateThrottleMs) {
                        val visibilityRatio = calculateVisibilityRatio(layoutCoordinates)
                        coordinator.updateVideoVisibility(
                            videoMid = videoMid,
                            tweetId = playbackTweetId,
                            visibilityRatio = visibilityRatio
                        )
                        lastVisibilityUpdate = now
                    }
                }
            }
            .clickable {
                // Auto-start video in full screen
                callback(index)
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = if (enableTapToShowControls) showControls else false // Use state for tap-to-show controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // Set background color to light gray (Material3 surface variant equivalent)
                    setBackgroundColor(android.graphics.Color.rgb(245, 245, 245))
                    // Keep last frame to avoid black flashes when resetting/pausing
                    setKeepContentOnPlayerReset(true)
                    // Disable built-in buffering indicator - we show our own CircularProgressIndicator
                    // This prevents duplicate loading spinners
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Force hardware acceleration and proper clipping for Media3 1.7.1
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
            },
            update = { playerView ->
                // Update player reference in case it changes (e.g., after recovery)
                // This fixes black screen issue when player is recreated
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
                // Update controller visibility when state changes
                if (enableTapToShowControls) {
                    playerView.useController = showControls // Use state for tap-to-show controls
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds() // Ensure content is clipped to bounds
                .then(
                    if (enableTapToShowControls) {
                        Modifier.clickable { 
                            showControls = !showControls
                        }
                    } else {
                        Modifier.clickable {
                            callback(index)
                        }
                    }
                )
        )

        // Show loading indicator when video is loading
        // Don't cover the entire video - allow frames to be visible while loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Show error state when video fails to load
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = stringResource(R.string.video_error),
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Video unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Show retry button for manual retry attempts
                    if (videoMid != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                retryCount++
                                hasError = false
                                isLoading = true
                                
                                Timber.tag("VideoPreview").d("Manual retry attempt $retryCount for video: $videoMid")
                                
                                // Attempt recovery on the main thread (required for ExoPlayer)
                                // Use lifecycle-aware scope to prevent memory leaks if composable is disposed
                                retryScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    try {
                                        // Force recreate with software decoder for manual retry
                                        val success = if (videoMid != null && url != null) {
                                            VideoManager.forceRecreatePlayer(context, videoMid, url, videoType)
                                        } else {
                                            false
                                        }
                                        if (success) {
                                            // Ensure playback resumes if visible and allowed
                                            if (isVideoVisible && autoPlay) {
                                                exoPlayer.playWhenReady = true
                                            }
                                            Timber.tag("VideoPreview").d("Manual retry successful for video: $videoMid")
                                        } else {
                                            // If recovery failed, show error again
                                            hasError = true
                                            isLoading = false
                                            Timber.tag("VideoPreview").w("Manual retry failed for video: $videoMid")
                                        }
                                    } catch (e: Exception) {
                                        // Only log retry failures at debug level to avoid noise
                                        Timber.d("VideoPreview - Manual retry failed: ${e.message}")
                                        hasError = true
                                        isLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = if (retryCount > 0) "Retry Again" else "Retry",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        // Show retry count information
                        if (retryCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Attempts: $retryCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // Mute button in lower right corner
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isMuted = !isMuted
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Time label in lower left corner
        if (showTimeLabel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.2f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatTime(remainingTime),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Format time in milliseconds to MM:SS format
 */
private fun formatTime(timeMs: Long): String {
    if (timeMs <= 0) return "0:00"
    
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    
    return if (minutes > 0) {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    } else {
        "0:${seconds.toString().padStart(2, '0')}"
    }
}
