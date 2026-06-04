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
    playbackVideoId: String? = null,
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
    val resolvedPlaybackVideoId = playbackVideoId ?: videoMid.orEmpty()
    val playerKey = if (shouldUseCoordinator) resolvedPlaybackVideoId else videoMid
    val isInFullScreen = videoMid?.let { VideoManager.isVideoInFullScreen(it) } == true
    val shouldPlay = if (isInFullScreen) {
        false
    } else if (shouldUseCoordinator) {
        state.coordinatorWantsToPlay
    } else {
        autoPlay
    }
    val effectivelyVisible = if (isInFullScreen) {
        false
    } else if (shouldUseCoordinator) {
        state.isVideoVisible || shouldPlay
    } else {
        state.isVideoVisible
    }
    val playerGeneration = VideoManager.playerGenerations[playerKey] ?: 0
    val preloadGeneration = VideoManager.preloadGenerations[playerKey] ?: 0
    val hasWarmPlayer = playerKey?.let { VideoManager.hasWarmVideoPlayer(it) } == true
    val shouldAcquirePlayer = !isInFullScreen && (
        !shouldUseCoordinator ||
            effectivelyVisible ||
            shouldPlay ||
            hasWarmPlayer
        )

    // --- Coordinator command collection ---
    LaunchedEffect(videoMid, playbackTweetId, resolvedPlaybackVideoId, coordinator) {
        val currentVideoMid = videoMid
        val currentPlaybackTweetId = playbackTweetId
        if (currentVideoMid == null || currentPlaybackTweetId == null) {
            state.coordinatorWantsToPlay = false
            return@LaunchedEffect
        }
        state.coordinatorWantsToPlay = coordinator.shouldAutoPlay(
            currentVideoMid,
            currentPlaybackTweetId,
            resolvedPlaybackVideoId
        )
        coordinator.playbackCommands.collect { command ->
            when (command) {
                is VideoPlaybackCommand.ShouldPlayVideo ->
                    if (command.playbackVideoId == resolvedPlaybackVideoId && command.videoMid == currentVideoMid) {
                        state.coordinatorWantsToPlay = true
                        // If this video becomes primary while in error, immediately retry.
                        // "Primary" reflects explicit user intent (tap) or coordinator focus.
                        if (state.hasError) {
                            state.manualRetry(context, url, videoType, retryScope)
                        }
                    } else {
                        state.coordinatorWantsToPlay = false
                    }
                is VideoPlaybackCommand.ShouldPauseVideo ->
                    if (command.playbackVideoId == resolvedPlaybackVideoId) state.coordinatorWantsToPlay = false
                is VideoPlaybackCommand.ShouldStopVideo ->
                    if (command.playbackVideoId == resolvedPlaybackVideoId) state.coordinatorWantsToPlay = false
                VideoPlaybackCommand.ShouldStopAllVideos ->
                    state.coordinatorWantsToPlay = false
            }
        }
    }

    // --- Visibility tracking via VideoLoadingManager ---
    DisposableEffect(playerKey, shouldAcquirePlayer, playerGeneration) {
        if (playerKey != null && shouldAcquirePlayer) {
            VideoManager.markVideoActive(playerKey)
        }
        onDispose {
            if (playerKey != null && shouldAcquirePlayer) {
                VideoManager.markVideoInactive(playerKey)
                VideoManager.cleanupInactivePlayersDeferred()
            }
        }
    }

    playerKey?.let { key ->
        rememberVideoLoadingManager(videoMid = key, isVisible = effectivelyVisible, generation = playerGeneration)
    }

    // --- ExoPlayer (regenerated on force-recreate) ---
    val cachedPoster = if (videoMid != null) VideoManager.posterBitmaps[videoMid] else null
    val exoPlayer = remember(playerKey, playerGeneration, preloadGeneration, shouldAcquirePlayer, url, videoType) {
        if (!shouldAcquirePlayer || url == null) return@remember null
        val resolvedHlsUrl = if (videoType == MediaType.HLS_VIDEO) {
            HlsUrlResolver.getCached(context, url)
        } else null
        val player = if (playerKey != null) {
            VideoManager.getVideoPlayer(
                context,
                playerKey,
                url,
                videoType,
                resolvedHlsUrl,
                mediaMid = videoMid ?: playerKey
            )
        } else {
            createExoPlayer(context, url, videoType ?: MediaType.Video, resolvedHlsUrl = resolvedHlsUrl)
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player
    }

    // --- Lifecycle observer (pause/resume) ---
    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    val currentShouldPlay by rememberUpdatedState(shouldPlay)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(
        lifecycleOwner,
        shouldUseCoordinator,
        videoMid,
        playbackTweetId,
        resolvedPlaybackVideoId,
        coordinator
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
                    currentExoPlayer?.playWhenReady = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    var resumeShouldPlay = currentShouldPlay
                    if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                        if (state.hasLastGeometry) {
                            val displayFrame = android.graphics.Rect()
                            rootView.getWindowVisibleDisplayFrame(displayFrame)
                            val visibleViewportTop = if (displayFrame.height() > 0) {
                                displayFrame.top.toFloat()
                            } else {
                                state.lastViewportTop
                            }
                            val visibleViewportBottom = if (displayFrame.height() > 0) {
                                displayFrame.bottom.toFloat()
                            } else {
                                state.lastViewportBottom
                            }
                            coordinator.updateVideoGeometry(
                                videoMid = videoMid,
                                playbackVideoId = resolvedPlaybackVideoId,
                                videoTop = state.lastVideoTop,
                                videoBottom = state.lastVideoBottom,
                                visibleViewportTop = visibleViewportTop,
                                visibleViewportBottom = visibleViewportBottom
                            )
                            coordinator.refreshViewportVisibilityFromGeometry()
                        } else {
                            coordinator.updateVideoVisibility(
                                videoMid,
                                playbackTweetId,
                                resolvedPlaybackVideoId,
                                state.lastVisibilityRatio
                            )
                        }
                        resumeShouldPlay = coordinator.shouldAutoPlay(
                            videoMid,
                            playbackTweetId,
                            resolvedPlaybackVideoId
                        )
                        state.coordinatorWantsToPlay = resumeShouldPlay
                    }
                    val vis = if (shouldUseCoordinator) (state.isVideoVisible || resumeShouldPlay) else state.isVideoVisible
                    if (vis && resumeShouldPlay) currentExoPlayer?.playWhenReady = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (videoMid != null && playbackTweetId != null) {
                coordinator.removeVideoGeometry(videoMid, resolvedPlaybackVideoId)
                coordinator.updateVideoVisibility(videoMid, playbackTweetId, resolvedPlaybackVideoId, 0f)
            }
        }
    }

    // --- Player listener (attach/detach with player) ---
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && videoMid != null) {
                    VideoManager.ensureVideoPoster(context, videoMid, url, videoType, null)
                }
                val player = currentExoPlayer ?: return
                val playbackVisible = if (shouldUseCoordinator) {
                    state.isVideoVisible || state.coordinatorWantsToPlay
                } else {
                    state.isVideoVisible
                }
                state.onPlaybackStateChanged(
                    playbackState, player, shouldUseCoordinator, autoPlay,
                    playbackVisible, coordinator, playbackTweetId, resolvedPlaybackVideoId, onLoadComplete, onVideoCompleted
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
        if (exoPlayer == null) {
            state.isLoading = false
            onDispose { }
        } else {
            exoPlayer.addListener(playerListener)
            when (exoPlayer.playbackState) {
                Player.STATE_READY -> state.observePlaybackProgress(
                    exoPlayer,
                    shouldPlay,
                    effectivelyVisible,
                    context,
                    url,
                    videoType,
                    retryScope
                )
                Player.STATE_BUFFERING, Player.STATE_IDLE -> state.isLoading = true
            }
            onDispose { exoPlayer.removeListener(playerListener) }
        }
    }

    // --- Playback state changes ---
    LaunchedEffect(state.isVideoVisible, shouldPlay, exoPlayer) {
        exoPlayer?.let {
            state.handlePlaybackStateChange(it, shouldPlay, effectivelyVisible, context, url, videoType, playerKey)
        }
    }

    LaunchedEffect(exoPlayer, shouldPlay, effectivelyVisible, url, videoType) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (true) {
            state.observePlaybackProgress(
                player,
                shouldPlay,
                effectivelyVisible,
                context,
                url,
                videoType,
                retryScope
            )
            delay(500L)
        }
    }

    // --- Mute state ---
    LaunchedEffect(state.isMuted, playerGeneration, exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
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
                findViewById<Button>(R.id.retry_button).setOnClickListener {
                    state.manualRetry(ctx, url, videoType, retryScope)
                }
                if (enableTapToShowControls) {
                    playerView.useController = true
                    playerView.controllerAutoShow = false
                    playerView.controllerShowTimeoutMs = 3000
                } else {
                    playerView.setOnClickListener { callback(index) }
                }
            }
        },
        update = { view ->
            val playerView = view.findViewById<PlayerView>(R.id.player_view)
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }

            val posterView = view.findViewById<ImageView>(R.id.video_poster)
            val showPoster = cachedPoster != null &&
                !state.isPlaying &&
                !state.hasError &&
                (state.isLoading || !shouldPlay)
            if (showPoster) {
                posterView.setImageBitmap(cachedPoster)
                posterView.visibility = View.VISIBLE
            } else {
                posterView.visibility = View.GONE
            }

            val playBtn = view.findViewById<ImageView>(R.id.play_button)
            playBtn.setOnClickListener {
                if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekTo(0)
                }
                if (videoMid != null && playbackTweetId != null) {
                    coordinator.requestPlay(videoMid, playbackTweetId, resolvedPlaybackVideoId)
                } else {
                    exoPlayer?.playWhenReady = true
                }
            }

            val showPlayButton = !enableTapToShowControls && !shouldPlay && !state.isPlaying && !state.hasError
            if (showPlayButton && playBtn.visibility != View.VISIBLE) {
                playBtn.alpha = 1f
                playBtn.visibility = View.VISIBLE
            } else if (!showPlayButton && playBtn.visibility == View.VISIBLE) {
                playBtn.animate().alpha(0f).setDuration(300).withEndAction {
                    playBtn.visibility = View.GONE
                }.start()
            }
            if (showPlayButton) {
                val density = view.resources.displayMetrics.density
                val playBgDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(87, 0, 0, 0))
                    setStroke(
                        (1f * density).toInt().coerceAtLeast(1),
                        android.graphics.Color.argb(115, 255, 255, 255)
                    )
                }
                playBtn.background = playBgDrawable
                playBtn.setColorFilter(android.graphics.Color.WHITE)
                playBtn.alpha = 0.78f
            }

            view.findViewById<ProgressBar>(R.id.loading_spinner).visibility =
                if (state.isLoading && shouldAcquirePlayer && shouldPlay) View.VISIBLE else View.GONE

            val errorView = view.findViewById<LinearLayout>(R.id.error_view)
            errorView.visibility = if (state.hasError) View.VISIBLE else View.GONE
            if (state.hasError && state.retryCount > 0) {
                val retryLabel = view.findViewById<TextView>(R.id.retry_count_label)
                retryLabel.visibility = View.VISIBLE
                retryLabel.text = "Attempts: ${state.retryCount}"
                view.findViewById<Button>(R.id.retry_button).text =
                    if (state.retryCount > 0) "Retry Again" else "Retry"
            }
        },
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { layoutCoordinates ->
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - state.lastVisibilityUpdate
                if (timeSinceLastUpdate < state.visibilityUpdateThrottleMs) return@onGloballyPositioned
                state.lastVisibilityUpdate = now
                val totalHeight = layoutCoordinates.size.height.toFloat()
                val displayFrame = android.graphics.Rect()
                rootView.getWindowVisibleDisplayFrame(displayFrame)
                val measuredVisibilityRatio = if (totalHeight > 0) {
                    val windowPos = layoutCoordinates.positionInWindow()
                    val videoTop = windowPos.y
                    val videoBottom = videoTop + totalHeight
                    val visibleTop = kotlin.math.max(displayFrame.top.toFloat(), videoTop)
                    val visibleBottom = kotlin.math.min(displayFrame.bottom.toFloat(), videoBottom)
                    val visibleHeight = kotlin.math.max(0f, visibleBottom - visibleTop)
                    (visibleHeight / totalHeight).coerceIn(0f, 1f)
                } else 0f
                val newVisibility = measuredVisibilityRatio > 0f
                if (state.isVideoVisible != newVisibility) {
                    state.isVideoVisible = newVisibility
                }
                state.lastVisibilityRatio = measuredVisibilityRatio
                if (videoMid != null && playbackTweetId != null) {
                    val windowPos = layoutCoordinates.positionInWindow()
                    state.hasLastGeometry = true
                    state.lastVideoTop = windowPos.y
                    state.lastVideoBottom = windowPos.y + totalHeight
                    state.lastViewportTop = displayFrame.top.toFloat()
                    state.lastViewportBottom = displayFrame.bottom.toFloat()
                    coordinator.updateVideoGeometry(
                        videoMid = videoMid,
                        playbackVideoId = resolvedPlaybackVideoId,
                        videoTop = state.lastVideoTop,
                        videoBottom = state.lastVideoBottom,
                        visibleViewportTop = state.lastViewportTop,
                        visibleViewportBottom = state.lastViewportBottom
                    )
                }
            }
            .then(if (enableTapToShowControls) Modifier else Modifier.clickable { callback(index) })
    )
}
