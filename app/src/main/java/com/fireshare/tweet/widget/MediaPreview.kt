package com.fireshare.tweet.widget

import android.net.Uri
import android.util.Log
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fireshare.tweet.R
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.MediaViewerParams
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.widget.Gadget.downloadFileHeader
import com.fireshare.tweet.widget.Gadget.getVideoDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class MediaType {
    Image, Video, Audio, PDF, Word, Excel, PPT, Zip, Txt, Html, Unknown
}

@Serializable
// url is in the format of http://ip/mm/mimei_id
data class MediaItem(val url: String, var type: MediaType = MediaType.Unknown)

@Composable
fun MediaPreviewGrid(
    mediaItems: List<MediaItem>,
    tweetId: MimeiId,
    containerWidth: Dp = 400.dp
) {    // need to check container width later
    val navController = LocalNavController.current
    val gridCells = if (mediaItems.size > 1) 2 else 1
    val maxItems = when (mediaItems.size) {
        1 -> 1
        in 2..3 -> 2
        else -> 4
    }
    val limitedMediaList = mediaItems.take(maxItems)

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCells),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        val modifier = if (gridCells == 1) Modifier
            .fillMaxWidth()
//            .aspectRatio(16f / 16f)
        else Modifier.size(containerWidth / gridCells)
        itemsIndexed(limitedMediaList) { index, mediaItem ->
            MediaItemPreview(
                limitedMediaList,
                modifier = modifier
                    .clickable {
                        val params = MediaViewerParams(mediaItems, index, tweetId)
                        navController.navigate(NavTweet.MediaViewer(params))
                    },
                /**
                 * If the last item previewed is not the last of the attachments, show a plus sign
                 * to indicate there are more items hidden.
                 * */
                isLastItem = mediaItem == limitedMediaList.last() && mediaItems.size > maxItems,
                index = index,      // autoplay first video item, index 0
            )
        }
    }
}

@Composable
fun MediaItemPreview(
    mediaItems: List<MediaItem>,
    modifier: Modifier = Modifier,
    isLastItem: Boolean = false,   // add a PLUS sign to indicate more items not shown
    index: Int = -1,               // autoplay first video item, index 0
    inPreviewGrid: Boolean = true,  // real aspectRatio when not displaying in preview grid.
    tweetId: MimeiId? = null
) {
    val mediaItem = mediaItems[index]
    val navController = LocalNavController.current
    val goto: (Int) -> Unit = { idx: Int ->
        tweetId?.let { navController.navigate(
            NavTweet.MediaViewer(MediaViewerParams(mediaItems, idx, it))) }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        when (mediaItem.type) {
            MediaType.Image -> {
                ImageViewer(mediaItem.url, modifier)
            }

            MediaType.Video -> {
                VideoPreview(url = mediaItem.url, modifier, index, inPreviewGrid) {
                    goto(index)
                }
            }

            MediaType.Audio -> {
                VideoPreview(url = mediaItem.url, modifier) {
                    goto(index)
                }
            }

            else -> {       // Handle unknown file type
                Log.e("MediaItemPreview", "unknown file type ${mediaItem.url}")
            }
        }
        if (isLastItem) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x40FFFFFF)), // Lighter shaded background
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(100.dp)
                        .alpha(0.7f)
                )
            }
        }
    }
}

/**
 * Download and compress image in cache
 * @param imageUrl: Leither image url in the format of
 *                  http://ip/ipfs/mimeiId
 * @param isPreview: download the original image to cache without compression if False
 * @param imageSize: compress download image below imageSize(KB)
 * */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isPreview: Boolean = true,
    imageSize: Int = 200    // Cache size in KB
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager(context) }
    val cachedPath = rememberUpdatedState(cacheManager.getCachedImagePath(imageUrl, isPreview))

    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf(false) }

    // Check if image is already cached and use it directly
    val cachedImage =
        remember(imageUrl) { mutableStateOf(cacheManager.loadImageFromCache(cachedPath.value)) }

    val adjustedModifier = if (isPreview) {
        modifier.fillMaxSize()      // image is viewed within a parent composable
    } else {
        modifier.fillMaxWidth()     // image is viewed full screen
    }
    if (cachedImage.value != null) {
        Box(modifier = modifier) {
            Image(
                painter = BitmapPainter(cachedImage.value!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = adjustedModifier
            )
        }
    } else {
        // Download and cache image if not already cached
        LaunchedEffect(cachedPath.value) {
            val job = scope.launch {
                isDownloading = true
                downloadError = false
                val downloadedPath = try {
                    cacheManager.downloadImageToCache(imageUrl, isPreview, imageSize)
                } catch (e: Exception) {
                    Log.e("ImageViewer", "Error downloading image: ${e.message}")
                    downloadError = true
                    null
                }
                isDownloading = false
                if (downloadedPath != null) {
                    cachedImage.value = cacheManager.loadImageFromCache(downloadedPath)
                }
            }
        }
        // Display light gray background while image is downloading
        if (isDownloading) {
            Box(
                modifier = adjustedModifier
                    .fillMaxSize()
                    .background(Color.Gray)
            )
        } else if (downloadError) {
            // Display a placeholder image or error message if download failed
            Box(
                modifier = adjustedModifier
                    .fillMaxSize()
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
            }
        }
    }
}

/**
 * @param index: when there are multiple videos in a grid, the first one is played automatically.
 * @param inPreviewGrid: If the video is previewed in a Grid as part of tweet item in a list.
 *                       The aspect ratio shall be 1:1, otherwise use the video's real aspectRatio.
 * **/
@Composable
fun VideoPreview(
    url: String,
    modifier: Modifier = Modifier,
    index: Int = -1,
    inPreviewGrid: Boolean = true,
    goto: (Int) -> Unit
) {
    val context = LocalContext.current
    val item = androidx.media3.common.MediaItem.fromUri(Uri.parse(url))
    val preferenceHelper = TweetApplication.preferenceHelper

    var isVideoVisible by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(preferenceHelper.getSpeakerMute()) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(item)
            prepare()
        }
    }

    var aspectRatio by remember { mutableFloatStateOf(1f) }
    if (!inPreviewGrid) {
        LaunchedEffect(url.substringAfterLast('/')) {
            val pair = getVideoDimensions(url) ?: Pair(400, 400)
            aspectRatio = pair.first.toFloat() / pair.second.toFloat()
        }
    } else {
        LaunchedEffect(url.substringAfterLast('/')) {
            val pair = Pair(400, 400)
            aspectRatio = pair.first.toFloat() / pair.second.toFloat()
        }
    }

    LaunchedEffect(isVideoVisible) {
        if (isVideoVisible && index == 0) {
            delay(500) // Wait for 0.5 seconds
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.playWhenReady = false
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted == true) 0f else 1f
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .onGloballyPositioned { layoutCoordinates ->
                isVideoVisible = isElementVisible(layoutCoordinates)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        areControlsVisible = !areControlsVisible
                        // Start a coroutine to hide controls after 2 seconds
                        scope.launch {
                            delay(1000)
                            areControlsVisible = false
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { PlayerView(context).apply {
                player = exoPlayer
                useController = true

                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                    areControlsVisible = visibility == View.VISIBLE
                })
            } },
            modifier = modifier
                .aspectRatio(aspectRatio)
        )
        if (areControlsVisible) {
            IconButton(
                onClick = { goto(index) },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_full_screen),
                    contentDescription = "Full screen",
                    tint = Color.White,
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize)
                        .alpha(0.8f)
                )
            }
            // Mute button
            IconButton(
                onClick = {
                    Log.d("VideoPreview", "Mute button clicked")
                    isMuted = !isMuted!!
                    preferenceHelper.setSpeakerMute(isMuted!!)
                },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Icon(
                    painter = painterResource(if (isMuted == true) R.drawable.ic_speaker_slash else R.drawable.ic_speaker),
                    contentDescription = if (isMuted == true) "Unmute" else "Mute",
                    tint = Color.White,
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize)
                        .alpha(0.8f)
                )
            }
        }
    }
}

fun isElementVisible(layoutCoordinates: LayoutCoordinates): Boolean {
    val threshold = 70
    val layoutHeight = layoutCoordinates.size.height
    val thresholdHeight = layoutHeight * threshold / 100
    val layoutTop = layoutCoordinates.positionInRoot().y
    val layoutBottom = layoutTop + layoutHeight

    // This should be parentLayoutCoordinates not parentCoordinates
    val parent =
        layoutCoordinates.parentLayoutCoordinates

    parent?.boundsInRoot()?.let { rect: Rect ->
        val parentTop = rect.top
        val parentBottom = rect.bottom

        val ret = if (
            parentBottom - layoutTop > thresholdHeight &&
            (parentTop < layoutBottom - thresholdHeight)
        ) {
            true
        } else {
            false
        }
        return ret
    }
    return false
}