package us.fireshare.tweet.widget

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * Lightweight video preview composable using XML PlayerView layout.
 * All mutable state and error handling logic is extracted into [VideoPreviewState].
 */
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
    useIndependentMuteState: Boolean = false,
    enableTapToShowControls: Boolean = false,
    playbackTweetId: MimeiId? = null,
    containerTopY: Float? = null
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val retryScope = rememberCoroutineScope()

    // --- State holder (all mutable state lives here) ---
    val state = rememberVideoPreviewState(videoMid, useIndependentMuteState)

    // --- Coordinator ---
    val coordinator = LocalVideoCoordinator.current
    val shouldUseCoordinator = playbackTweetId != null && videoMid != null
    val shouldPlay = if (shouldUseCoordinator) state.coordinatorWantsToPlay else autoPlay
    val effectivelyVisible = if (shouldUseCoordinator) (state.isVideoVisible || shouldPlay) else state.isVideoVisible

    // --- Coordinator command collection ---
    LaunchedEffect(videoMid, playbackTweetId, coordinator) {
        if (!shouldUseCoordinator) {
            state.coordinatorWantsToPlay = false
            return@LaunchedEffect
        }
        state.coordinatorWantsToPlay = coordinator.shouldAutoPlay(videoMid, playbackTweetId)
        coordinator.playbackCommands.collect { command ->
            when (command) {
                is VideoPlaybackCommand.ShouldPlayVideo ->
                    state.coordinatorWantsToPlay = command.videoMid == videoMid && command.tweetId == playbackTweetId
                is VideoPlaybackCommand.ShouldPauseVideo ->
                    if (command.videoMid == videoMid) state.coordinatorWantsToPlay = false
                is VideoPlaybackCommand.ShouldStopVideo ->
                    if (command.videoMid == videoMid) state.coordinatorWantsToPlay = false
                VideoPlaybackCommand.ShouldStopAllVideos ->
                    state.coordinatorWantsToPlay = false
            }
        }
    }

    // --- Visibility tracking via VideoLoadingManager ---
    videoMid?.let { mid ->
        rememberVideoLoadingManager(videoMid = mid, isVisible = effectivelyVisible)
    }

    // --- ExoPlayer (regenerated on force-recreate) ---
    val playerGeneration = VideoManager.playerGenerations[videoMid] ?: 0
    val exoPlayer = remember(videoMid, playerGeneration) {
        val resolvedHlsUrl = if (videoType == MediaType.HLS_VIDEO && url != null) {
            HlsUrlResolver.getCached(context, url)
        } else null
        val player = if (videoMid != null && url != null) {
            VideoManager.getVideoPlayer(context, videoMid, url, videoType, resolvedHlsUrl)
        } else if (url != null) {
            createExoPlayer(context, url, videoType ?: MediaType.Video, resolvedHlsUrl = resolvedHlsUrl)
        } else {
            createExoPlayer(context, "", videoType ?: MediaType.Video)
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player
    }

    // --- Lifecycle observer (pause/resume) ---
    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    val currentShouldPlay by rememberUpdatedState(shouldPlay)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
                    currentExoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    val vis = if (shouldUseCoordinator) (state.isVideoVisible || currentShouldPlay) else state.isVideoVisible
                    if (vis && currentShouldPlay) currentExoPlayer.playWhenReady = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                coordinator.updateVideoVisibility(videoMid, playbackTweetId, 0f)
            }
        }
    }

    // --- Player listener (attach/detach with player) ---
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.onPlaybackStateChanged(
                    playbackState, currentExoPlayer, shouldUseCoordinator, autoPlay,
                    state.isVideoVisible, coordinator, playbackTweetId, onLoadComplete, onVideoCompleted
                )
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                state.onPlayerError(error, context, url, videoType, retryScope)
            }
        }
    }

    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        // Sync loading state with actual player state
        when (exoPlayer.playbackState) {
            Player.STATE_READY -> state.isLoading = false
            Player.STATE_BUFFERING, Player.STATE_IDLE -> state.isLoading = true
        }
        onDispose { exoPlayer.removeListener(playerListener) }
    }

    // --- Playback state changes ---
    LaunchedEffect(state.isVideoVisible, shouldPlay) {
        state.handlePlaybackStateChange(exoPlayer, shouldPlay, effectivelyVisible, context, url, videoType)
    }

    // --- Mute state ---
    LaunchedEffect(state.isMuted, playerGeneration) {
        try {
            exoPlayer.volume = if (state.isMuted) 0f else 1f
            if (!useIndependentMuteState) preferenceHelper.setSpeakerMute(state.isMuted)
        } catch (e: Exception) {
            Timber.e("VideoPreview - Error setting volume: ${e.message}")
        }
    }

    LaunchedEffect(useIndependentMuteState) {
        if (useIndependentMuteState) return@LaunchedEffect
        preferenceHelper.speakerMuteFlow.collect { globalMuteState ->
            if (state.isMuted != globalMuteState) state.isMuted = globalMuteState
        }
    }

    // --- Time label auto-show/hide ---
    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            state.showTimeLabel = true
            delay(5000)
            state.showTimeLabel = false
        }
    }

    LaunchedEffect(state.showTimeLabel) {
        while (state.showTimeLabel) {
            state.remainingTime = exoPlayer.duration - exoPlayer.currentPosition
            delay(1000)
        }
    }

    // --- Auto-hide controls ---
    LaunchedEffect(state.showControls) {
        if (state.showControls && enableTapToShowControls) {
            delay(2000)
            state.showControls = false
        }
    }

    // --- XML PlayerView layout ---
    AndroidView(
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.video_player_view, null).apply {
                val playerView = findViewById<PlayerView>(R.id.player_view)
                playerView.player = exoPlayer
                // Listen for video size changes for aspect ratio
                exoPlayer.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                            playerView.setAspectRatioListener { _, _, _ -> }
                        }
                    }
                })
                // Mute button click
                findViewById<ImageView>(R.id.mute_button).setOnClickListener {
                    state.toggleMute()
                }
                // Play button click handler is set in the update block so it always
                // references the current exoPlayer (factory closures go stale after
                // player recreation via playerGeneration changes).
                // Retry button click
                findViewById<Button>(R.id.retry_button).setOnClickListener {
                    state.manualRetry(ctx, url, videoType, retryScope)
                }
                // Tap handling
                if (enableTapToShowControls) {
                    playerView.setOnClickListener { state.showControls = !state.showControls }
                } else {
                    playerView.setOnClickListener { callback(index) }
                }
            }
        },
        update = { view ->
            val playerView = view.findViewById<PlayerView>(R.id.player_view)
            // Rebind player on generation change
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
            // Re-wire play button on every update so it always references the current
            // exoPlayer and coordinator (factory closures go stale after player recreation).
            val playBtn = view.findViewById<ImageView>(R.id.play_button)
            playBtn.setOnClickListener {
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                    coordinator.requestPlay(videoMid, playbackTweetId)
                } else {
                    exoPlayer.playWhenReady = true
                }
            }
            // Play button (show when not playing, not loading, not error)
            val showPlayButton = !state.isPlaying && !state.isLoading && !state.hasError
            if (showPlayButton && playBtn.visibility != View.VISIBLE) {
                playBtn.alpha = 1f
                playBtn.visibility = View.VISIBLE
            } else if (!showPlayButton && playBtn.visibility == View.VISIBLE) {
                playBtn.animate().alpha(0f).setDuration(300).withEndAction {
                    playBtn.visibility = View.GONE
                }.start()
            }
            if (showPlayButton) {
                val playBgDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(200, 33, 150, 243))
                    setStroke(4, android.graphics.Color.WHITE)
                }
                playBtn.background = playBgDrawable
                playBtn.setColorFilter(android.graphics.Color.WHITE)
            }
            // Loading spinner
            view.findViewById<ProgressBar>(R.id.loading_spinner).visibility =
                if (state.isLoading) View.VISIBLE else View.GONE
            // Error view
            val errorView = view.findViewById<LinearLayout>(R.id.error_view)
            errorView.visibility = if (state.hasError) View.VISIBLE else View.GONE
            if (state.hasError && state.retryCount > 0) {
                val retryLabel = view.findViewById<TextView>(R.id.retry_count_label)
                retryLabel.visibility = View.VISIBLE
                retryLabel.text = "Attempts: ${state.retryCount}"
                view.findViewById<Button>(R.id.retry_button).text =
                    if (state.retryCount > 0) "Retry Again" else "Retry"
            }
            // Mute button icon (same foreground/background as time label)
            val muteBtn = view.findViewById<ImageView>(R.id.mute_button)
            muteBtn.setImageResource(
                if (state.isMuted) android.R.drawable.ic_lock_silent_mode
                else android.R.drawable.ic_lock_silent_mode_off
            )
            muteBtn.setColorFilter(android.graphics.Color.argb(153, 255, 255, 255)) // #99FFFFFF, same as time_label
            muteBtn.alpha = if (state.isMuted) 0.6f else 0.8f
            muteBtn.setBackgroundResource(0)
            // Same background shade as time label: argb(51, 0, 0, 0)
            val muteBgDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(51, 0, 0, 0))
            }
            muteBtn.background = muteBgDrawable
            // Time label
            val timeLabel = view.findViewById<TextView>(R.id.time_label)
            if (state.showTimeLabel) {
                timeLabel.visibility = View.VISIBLE
                timeLabel.text = VideoPreviewState.formatTime(state.remainingTime)
                // Apply rounded background
                val timeBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4f * view.resources.displayMetrics.density
                    setColor(android.graphics.Color.argb(51, 0, 0, 0))
                }
                timeLabel.background = timeBg
            } else {
                timeLabel.visibility = View.GONE
            }
        },
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { layoutCoordinates ->
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - state.lastVisibilityUpdate
                // Skip expensive position/visibility calculations during rapid scroll
                if (timeSinceLastUpdate < 200L) return@onGloballyPositioned
                state.lastVisibilityUpdate = now
                val totalHeight = layoutCoordinates.size.height.toFloat()
                val visibilityRatio = if (totalHeight > 0) {
                    val windowPos = layoutCoordinates.positionInWindow()
                    val videoTop = windowPos.y
                    val videoBottom = videoTop + totalHeight
                    val displayFrame = android.graphics.Rect()
                    rootView.getWindowVisibleDisplayFrame(displayFrame)
                    val visibleTop = kotlin.math.max(displayFrame.top.toFloat(), videoTop)
                    val visibleBottom = kotlin.math.min(displayFrame.bottom.toFloat(), videoBottom)
                    val visibleHeight = kotlin.math.max(0f, visibleBottom - visibleTop)
                    (visibleHeight / totalHeight).coerceIn(0f, 1f)
                } else 0f
                val newVisibility = visibilityRatio >= 0.5f
                if (state.isVideoVisible != newVisibility) {
                    state.isVideoVisible = newVisibility
                }
                if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                    coordinator.updateVideoVisibility(videoMid, playbackTweetId, visibilityRatio)
                }
            }
            .clickable { callback(index) }
    )
}
