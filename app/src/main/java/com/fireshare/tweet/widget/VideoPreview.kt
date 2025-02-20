package com.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fireshare.tweet.HproseInstance.preferenceHelper
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import com.fireshare.tweet.widget.Gadget.isElementVisible
import com.fireshare.tweet.widget.VideoCacheManager.getVideoDimensions
import kotlinx.coroutines.delay

/**
 * @param index: when there are multiple videos in a grid, the first one is played automatically.
 * @param inPreviewGrid: If the video is previewed in a Grid as part of tweet item in a list.
 *                       The aspect ratio shall be 1:1, otherwise use the video's real aspectRatio.
 * @param callback: callback function to be performed when video is closed.
 * **/
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    url: String,
    modifier: Modifier,
    index: Int,
    autoPlay: Boolean = false,
    inPreviewGrid: Boolean = true,
    aspectRatio: Float?,
    callback: (Int) -> Unit
) {
    val context = LocalContext.current
    var isVideoVisible by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(preferenceHelper.getSpeakerMute()) }
    val exoPlayer = remember { createExoPlayer(context, url) }

    /**
     * Stop playing when screen is locked or closed. Resume play when unlocked.
     * */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        // Do not play video by default.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Pause or stop video playback here
                    exoPlayer.playWhenReady = false
                }

                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    // Resume video playback here (if needed)
                    exoPlayer.playWhenReady = true
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isVideoVisible) {
        if (isVideoVisible) {
            exoPlayer.prepare()
            delay(500)
            exoPlayer.playWhenReady = autoPlay
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.stop()
        }
    }

    var videoRatio by remember { mutableFloatStateOf(aspectRatio?: 1f) }
    LaunchedEffect(url.getMimeiKeyFromUrl()) {
        if (aspectRatio == null) {
            val (width, height) = getVideoDimensions(url) ?: Pair(400, 400)
            videoRatio = width.toFloat() / height.toFloat()
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // When previewing a single video, limit its height to show more content.
//    val boxModifier = if (inPreviewGrid) modifier.heightIn(max = 500.dp) else modifier
    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { layoutCoordinates ->
                isVideoVisible = isElementVisible(layoutCoordinates)
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = !inPreviewGrid
                    controllerShowTimeoutMs = 2000
                    controllerAutoShow = !inPreviewGrid
                    hideController()
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = modifier.fillMaxWidth()
        )
        // Mute button
        IconButton(
            onClick = {
                isMuted = !isMuted
                preferenceHelper.setSpeakerMute(isMuted)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
        ) {
            Icon(
                painter = painterResource(if (isMuted) R.drawable.ic_speaker_slash else R.drawable.ic_speaker),
                contentDescription = if (isMuted) "UnMute" else "Mute",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .size(ButtonDefaults.IconSize)
            )
        }

        if ( ! inPreviewGrid) {
            // Show full screen button
            IconButton(
                onClick = { callback(index) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_full_screen),
                    contentDescription = "Full screen",
                    tint = Color.White,
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize)
                )
            }
        }
    }
}
