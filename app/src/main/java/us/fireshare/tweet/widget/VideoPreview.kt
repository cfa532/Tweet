package us.fireshare.tweet.widget

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * Compose-only video preview. The video surface is hosted in a programmatically-created
 * PlayerView (wrapped in AndroidView); every other UI element — poster, play button,
 * loading spinner, error view — is a native Compose composable.
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

    // Reference to the AspectRatioFrameLayout hosting the TextureView (non-controls path).
    // Updated from onVideoSizeChanged so the frame can resize to the actual video AR.
    val frameLayoutRef = remember { mutableStateOf<AspectRatioFrameLayout?>(null) }

    // --- Coordinator ---
    val coordinator = LocalVideoCoordinator.current
    val shouldUseCoordinator = playbackTweetId != null && videoMid != null
    val resolvedPlaybackVideoId = playbackVideoId ?: videoMid.orEmpty()
    val shouldPlay = if (shouldUseCoordinator) state.coordinatorWantsToPlay else autoPlay
    val effectivelyVisible = if (shouldUseCoordinator) (state.isVideoVisible || shouldPlay) else state.isVideoVisible
    var visibilityRatio by remember(videoMid) { mutableFloatStateOf(0f) }
    val preloadGeneration = VideoManager.preloadGenerations[videoMid] ?: 0
    val hasWarmPlayer = videoMid?.let { VideoManager.isVideoPreloaded(it) } == true
    val shouldAcquirePlayer = !shouldUseCoordinator ||
        shouldPlay ||
        visibilityRatio >= 0.35f ||
        (hasWarmPlayer && visibilityRatio > 0f)

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
    DisposableEffect(videoMid, shouldAcquirePlayer) {
        if (videoMid != null && shouldAcquirePlayer) {
            VideoManager.markVideoActive(videoMid)
        }
        onDispose {
            if (videoMid != null && shouldAcquirePlayer) {
                VideoManager.markVideoInactive(videoMid)
                VideoManager.cleanupInactivePlayersDeferred()
            }
        }
    }

    videoMid?.let { mid ->
        rememberVideoLoadingManager(videoMid = mid, isVisible = effectivelyVisible)
    }

    // --- ExoPlayer (regenerated on force-recreate) ---
    val playerGeneration = VideoManager.playerGenerations[videoMid] ?: 0
    val cachedPoster = if (videoMid != null) VideoManager.posterBitmaps[videoMid] else null
    val exoPlayer = remember(videoMid, playerGeneration, preloadGeneration, shouldAcquirePlayer, url, videoType) {
        if (!shouldAcquirePlayer || url == null) return@remember null
        val resolvedHlsUrl = if (videoType == MediaType.HLS_VIDEO) {
            HlsUrlResolver.getCached(context, url)
        } else null
        val player = if (videoMid != null) {
            VideoManager.getVideoPlayer(context, videoMid, url, videoType, resolvedHlsUrl)
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

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP ->
                    currentExoPlayer?.playWhenReady = false
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    val vis = if (shouldUseCoordinator) (state.isVideoVisible || currentShouldPlay) else state.isVideoVisible
                    if (vis && currentShouldPlay) currentExoPlayer?.playWhenReady = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (videoMid != null && playbackTweetId != null) {
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
                state.onPlaybackStateChanged(
                    playbackState, player, shouldUseCoordinator, autoPlay,
                    state.isVideoVisible, coordinator, playbackTweetId, resolvedPlaybackVideoId, onLoadComplete, onVideoCompleted
                )
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.isPlaying = isPlaying
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Push the real video AR into AspectRatioFrameLayout (TextureView path only).
                val ratio = if (videoSize.height > 0) {
                    videoSize.width.toFloat() / videoSize.height.toFloat()
                } else 0f
                frameLayoutRef.value?.setAspectRatio(ratio)
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
                Player.STATE_READY -> state.isLoading = false
                Player.STATE_BUFFERING, Player.STATE_IDLE -> state.isLoading = true
            }
            onDispose { exoPlayer.removeListener(playerListener) }
        }
    }

    // --- Playback state changes ---
    LaunchedEffect(state.isVideoVisible, shouldPlay, exoPlayer) {
        exoPlayer?.let {
            state.handlePlaybackStateChange(it, shouldPlay, effectivelyVisible, context, url, videoType)
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

    val showPoster = cachedPoster != null &&
        !state.isPlaying &&
        !state.hasError &&
        (state.isLoading || !shouldPlay)
    val showPlayButton = !enableTapToShowControls && !state.isPlaying && !state.isLoading && !state.hasError

    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { layoutCoordinates ->
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - state.lastVisibilityUpdate
                if (timeSinceLastUpdate < 200L) return@onGloballyPositioned
                state.lastVisibilityUpdate = now
                val totalHeight = layoutCoordinates.size.height.toFloat()
                val measuredVisibilityRatio = if (totalHeight > 0) {
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
                visibilityRatio = measuredVisibilityRatio
                val newVisibility = measuredVisibilityRatio >= 0.5f
                if (state.isVideoVisible != newVisibility) {
                    state.isVideoVisible = newVisibility
                }
                if (videoMid != null && playbackTweetId != null) {
                    coordinator.updateVideoVisibility(videoMid, playbackTweetId, resolvedPlaybackVideoId, measuredVisibilityRatio)
                }
            }
            // When native controls are enabled, let the PlayerView intercept taps so its
            // controller can show/hide. Otherwise route taps to the caller's callback.
            .then(
                if (enableTapToShowControls) Modifier
                else Modifier.clickable { callback(index) }
            )
    ) {
        // Video surface. Two paths:
        //  1) enableTapToShowControls = true: PlayerView (needed for native controller UI).
        //     Used by the detail view, which shows ONE video — no risk of the SurfaceView
        //     bleed bug where adjacent video surfaces overlap each other.
        //  2) enableTapToShowControls = false: AspectRatioFrameLayout + TextureView. This
        //     replicates what PlayerView does internally with surface_type="texture_view"
        //     and avoids the SurfaceView z-order bug that lets one video draw over another
        //     in multi-video grids.
        if (enableTapToShowControls) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        controllerAutoShow = false
                        controllerShowTimeoutMs = 3000
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setBackgroundColor(android.graphics.Color.parseColor("#FFF5F5F5"))
                        player = exoPlayer
                    }
                },
                update = { playerView ->
                    if (playerView.player !== exoPlayer) {
                        playerView.player = exoPlayer
                    }
                }
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    AspectRatioFrameLayout(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setBackgroundColor(android.graphics.Color.parseColor("#FFF5F5F5"))
                        val textureView = TextureView(ctx)
                        addView(
                            textureView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        exoPlayer?.setVideoTextureView(textureView)
                        // Initial aspect ratio if the player already knows the video size.
                        val vs = exoPlayer?.videoSize
                        if (vs != null && vs.height > 0) {
                            setAspectRatio(vs.width.toFloat() / vs.height.toFloat())
                        }
                        frameLayoutRef.value = this
                    }
                },
                update = { frame ->
                    val tv = frame.getChildAt(0) as? TextureView ?: return@AndroidView
                    // Re-attach when the player generation changes.
                    exoPlayer?.setVideoTextureView(tv)
                    val vs = exoPlayer?.videoSize
                    if (vs != null && vs.height > 0) {
                        frame.setAspectRatio(vs.width.toFloat() / vs.height.toFloat())
                    }
                }
            )
        }

        // Poster (shown while loading / before first play)
        if (showPoster && cachedPoster != null) {
            Image(
                bitmap = cachedPoster.asImageBitmap(),
                contentDescription = stringResource(R.string.video_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Centered play button (hidden when native controls are enabled)
        AnimatedVisibility(
            visible = showPlayButton,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xC82196F3))
                    .border(2.dp, Color.White, CircleShape)
                    .clickable {
                        if (exoPlayer?.playbackState == Player.STATE_ENDED) {
                            exoPlayer.seekTo(0)
                        }
                        if (videoMid != null && playbackTweetId != null) {
                            coordinator.requestPlay(videoMid, playbackTweetId, resolvedPlaybackVideoId)
                        } else {
                            exoPlayer?.playWhenReady = true
                        }
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.play),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Loading spinner
        if (state.isLoading && shouldAcquirePlayer) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }

        // Error view
        if (state.hasError) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(44.dp)
                        .clickable {
                            if (shouldUseCoordinator && videoMid != null && playbackTweetId != null) {
                                coordinator.requestPlay(videoMid, playbackTweetId, resolvedPlaybackVideoId)
                            }
                            state.manualRetry(context, url, videoType, retryScope)
                        }
                )
            }
        }
    }
}
