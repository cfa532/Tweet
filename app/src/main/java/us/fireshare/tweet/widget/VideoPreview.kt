package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
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
            
            // Ensure player is in a good state before playing
            if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                Timber.d("VideoPreview - Player in IDLE state, preparing again")
                exoPlayer.prepare()
            }
            
            // Set playWhenReady after ensuring player is ready
            Timber.d("VideoPreview - Setting playWhenReady to: $autoPlay")
            exoPlayer.playWhenReady = autoPlay
        } else {
            Timber.d("VideoPreview - Video no longer visible, pausing playback")
            // Only pause if this is the only active instance of this video
            // Don't pause if the video is being used in full screen
            videoMid?.let { mid ->
                val activeCount = VideoManager.getVideoActiveCount(mid)
                if (activeCount <= 1) {
                    exoPlayer.playWhenReady = false
                } else {
                    Timber.d("VideoPreview - Not pausing video $mid as it's still active in other views (count: $activeCount)")
                }
            } ?: run {
                // If no videoMid, this is a standalone player, so pause it
                exoPlayer.playWhenReady = false
            }
        }
    }

    var videoRatio by remember { mutableFloatStateOf(aspectRatio ?: (16f / 9f)) }
    LaunchedEffect(url.getMimeiKeyFromUrl()) {
        if (aspectRatio == null) {
            // Default to 16:9 aspect ratio if not provided
            videoRatio = 16f / 9f
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
            .clickable {
                Timber.d("VideoPreview - Video tapped at index: $index, calling callback")
                callback(index)
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // No controls in preview mode
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
