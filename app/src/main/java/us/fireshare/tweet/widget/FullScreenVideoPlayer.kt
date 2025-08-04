package us.fireshare.tweet.widget

import android.app.Activity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    videoMid: MimeiId,
    videoUrl: String,
    onClose: () -> Unit,
    autoPlay: Boolean = true, // Auto-start playback when entering full screen
    enableImmersiveMode: Boolean = true,
    autoReplay: Boolean = true, // Auto-replay when video ends
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // -1 for left, 1 for right
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Get the dedicated full screen player
    val exoPlayer = remember {
        FullScreenVideoManager.getFullScreenPlayer(context)
    }

    // Load video into the full screen player
    LaunchedEffect(videoMid, videoUrl) {
        FullScreenVideoManager.loadVideo(context, videoMid, videoUrl)
    }

    // Start playback when entering full screen
    LaunchedEffect(Unit) {
        // Set volume to 1f (unmuted) for full screen
        FullScreenVideoManager.setVolume(1f)

        // Start playback with auto-replay
        FullScreenVideoManager.startPlayback(autoReplay)
        
        // Auto-play the video
        exoPlayer.playWhenReady = true
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                val decorView = act.window.decorView
                val flags = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                decorView.systemUiVisibility = flags
            }
        }

        onDispose {
            // Show system bars on exit
            if (enableImmersiveMode) {
                activity?.let { act ->
                    val decorView = act.window.decorView
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    // Handle auto-replay
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && autoReplay) {
                    exoPlayer.seekTo(0)
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Full screen video player UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (abs(dragOffset) > 100f) {
                            onHorizontalSwipe?.invoke(if (dragOffset > 0) 1 else -1)
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragOffset += dragAmount.x
                    }
                )
            }
    ) {
        // Video player view
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // We'll implement custom controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable { showControls = !showControls } // Toggle controls on tap
        )

        // Custom controls overlay
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = !showControls }
            ) {
                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Play/Pause button
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.playWhenReady = false
                        } else {
                            exoPlayer.playWhenReady = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (exoPlayer.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Auto-hide controls after 3 seconds
        LaunchedEffect(showControls) {
            if (showControls) {
                delay(3000)
                showControls = false
            }
        }
    }
}

/**
 * FullScreenVideoPlayer that uses an existing player instance
 * This allows seamless transition from preview to full-screen without losing position
 */
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    existingPlayer: ExoPlayer,
    videoMid: MimeiId,
    onClose: () -> Unit,
    enableImmersiveMode: Boolean = true,
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Use the existing player for full-screen mode
    LaunchedEffect(Unit) {
        FullScreenVideoManager.useExistingPlayer(existingPlayer, videoMid)
        // Set volume to 1f (unmuted) for full screen
        FullScreenVideoManager.setVolume(1f)
        
        // Auto-play the video
        existingPlayer.playWhenReady = true
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                val decorView = act.window.decorView
                val flags = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                decorView.systemUiVisibility = flags
            }
        }

        onDispose {
            // Show system bars on exit
            if (enableImmersiveMode) {
                activity?.let { act ->
                    val decorView = act.window.decorView
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            // Return player to preview mode
            VideoManager.returnFromFullScreen(videoMid)
        }
    }

    // Full screen video player UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (abs(dragOffset) > 100f) {
                            onHorizontalSwipe?.invoke(if (dragOffset > 0) 1 else -1)
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragOffset += dragAmount.x
                    }
                )
            }
    ) {
        // Video player view
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = existingPlayer
                    useController = false // We'll implement custom controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable { showControls = !showControls } // Toggle controls on tap
        )

        // Custom controls overlay
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = !showControls }
            ) {
                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Play/Pause button
                IconButton(
                    onClick = {
                        if (existingPlayer.isPlaying) {
                            existingPlayer.playWhenReady = false
                        } else {
                            existingPlayer.playWhenReady = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (existingPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (existingPlayer.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Auto-hide controls after 3 seconds
        LaunchedEffect(showControls) {
            if (showControls) {
                delay(3000)
                showControls = false
            }
        }
    }
}