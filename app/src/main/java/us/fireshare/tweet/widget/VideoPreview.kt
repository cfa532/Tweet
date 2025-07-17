package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
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
    url: String,
    modifier: Modifier,
    index: Int,
    autoPlay: Boolean = false,
    inPreviewGrid: Boolean = true,
    aspectRatio: Float?,
    callback: (Int) -> Unit,
    videoMid: MimeiId? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isVideoVisible by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(preferenceHelper.getSpeakerMute()) }
    var showControls by remember { mutableStateOf(false) }
    
    val exoPlayer = remember(url, videoMid) { 
        if (videoMid != null) {
            Timber.d("VideoPreview - Getting ExoPlayer from VideoManager for video: $videoMid")
            VideoManager.getVideoPlayer(context, videoMid, url)
        } else {
            Timber.d("VideoPreview - Creating NEW ExoPlayer for URL: $url (no videoMid)")
            createExoPlayer(context, url, MediaType.Video)
        }
    }

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
                    // Resume video playback if it was playing before
                    if (isVideoVisible && autoPlay) {
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
        }
    }

    LaunchedEffect(isVideoVisible) {
        if (isVideoVisible) {
            Timber.d("VideoPreview - Video became visible, autoPlay: $autoPlay")
            // Mark video as active in VideoManager
            videoMid?.let { mid ->
                VideoManager.markVideoActive(mid)
            }
            
            // Player is already prepared when created, just set playWhenReady
            Timber.d("VideoPreview - Setting playWhenReady to: $autoPlay")
            exoPlayer.playWhenReady = autoPlay
        } else {
            Timber.d("VideoPreview - Video no longer visible, pausing playback")
            // Just pause playback, don't stop or destroy the player
            exoPlayer.playWhenReady = false
        }
    }

    var videoRatio by remember { mutableFloatStateOf(aspectRatio?: 16f/9f) }
    LaunchedEffect(url.getMimeiKeyFromUrl()) {
        if (aspectRatio == null) {
            // Default to 16:9 aspect ratio if not provided
            videoRatio = 16f / 9f
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    // Auto-hide controls after 2 seconds
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(2000)
            showControls = false
        }
    }

    // When previewing a single video, limit its height to show more content.
//    val boxModifier = if (inPreviewGrid) modifier.heightIn(max = 500.dp) else modifier
    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { layoutCoordinates ->
                isVideoVisible = isElementVisible(layoutCoordinates)
            }
            .clickable {
                showControls = !showControls
                if (showControls) {
                    // Reset the auto-hide timer when user touches again
                    coroutineScope.launch {
                        delay(2000)
                        showControls = false
                    }
                }
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = showControls && !inPreviewGrid
                    controllerShowTimeoutMs = 2000
                    controllerAutoShow = false
                    if (!showControls) hideController()
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        // Mute button - only show when controls are visible
        if (showControls) {
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
        }

        if (!inPreviewGrid && showControls) {
            // Show full screen button - only when controls are visible
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
