package com.fireshare.tweet.widget

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.widget.Gadget.downloadFileHeader
import com.fireshare.tweet.widget.Gadget.getVideoDimensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
sealed class MediaType {
    @Serializable
    data object Video : MediaType()
    @Serializable
    data object Audio : MediaType()
    @Serializable
    data object Image : MediaType()
    @Serializable
    data object Unknown : MediaType()
}

@Serializable
// url is in the format of http://ip/mm/mimei_id
data class MediaItem( val url: String, var type: MediaType = MediaType.Image )

@Composable
fun MediaPreviewGrid(mediaItems: List<MediaItem>, containerWidth: Dp = 400.dp) {    // need to check container width later
    val navController = LocalNavController.current
    val gridCells = if (mediaItems.size>1) 2 else 1
    val maxItems = when(mediaItems.size) {
        1 -> 1
        in 2..3 -> 2
        else -> 4
    }
    val limitedMediaList = mediaItems.take(maxItems)

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCells),
    ) {
        items(limitedMediaList) { mediaItem ->
            MediaItemPreview(mediaItem,
                Modifier.size(containerWidth/gridCells)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        val index = mediaItems.indexOf(mediaItem)
                        navController.navigate(NavTweet.MediaViewer(
                            mediaItems.map {it.url}, index))
                    },
                isLastItem = mediaItem == limitedMediaList.last() && mediaItems.size > maxItems,
                index = if (mediaItems.indexOf(mediaItem) == 0) 0 else -1,
            )
        }
    }
}

@Composable
fun MediaItemPreview(mediaItem: MediaItem,
                     modifier: Modifier = Modifier,
                     isLastItem: Boolean = false,   // add a PLUS sign to indicate more items not shown
                     index: Int = -1,               // autoplay first video item, index 0
                     inPreviewGrid: Boolean = true  // real aspectRatio when not displaying in preview grid.
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(mediaItem.url.substringAfterLast("/")) {
        coroutineScope.launch {
            val header = withContext(Dispatchers.IO) {
                downloadFileHeader(mediaItem.url)
            }
            detectMimeTypeFromHeader(header)?.let {
                mediaItem.type = when (it.substringBefore("/")) {
                    "image" -> MediaType.Image
                    "audio" -> MediaType.Audio
                    "video" -> MediaType.Video
                    else -> MediaType.Unknown
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        when (mediaItem.type) {
            MediaType.Image -> {
                ImageViewer(mediaItem.url, modifier)
            }
            MediaType.Video -> {
                VideoPreview(url = mediaItem.url, modifier, index, inPreviewGrid)
            }
            MediaType.Audio ->  {
                VideoPreview(url = mediaItem.url, modifier)
            }
            else -> {       // Handle unknown file type
                Log.d("MediaItemPreview", "unknown file type ${mediaItem.url}")
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
                    modifier = Modifier.size(100.dp)
                        .alpha(0.7f)
                )
            }
        }
    }
}

@Composable
fun ImageViewer(imageUrl: String, modifier: Modifier = Modifier, isPreview: Boolean = true) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager(context) }
    val cachedPath = cacheManager.getCachedImagePath(imageUrl, isPreview)

    // State to track if the image is being downloaded
    var isDownloading by remember { mutableStateOf(false) }

    // Check if image is already cached and use it directly
    val cachedImage = cacheManager.loadImageFromCache(cachedPath)
    if (cachedImage != null) {
        Box(modifier = modifier) {
            Image(
                painter = BitmapPainter(cachedImage),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        // Download and cache image if not already cached
        LaunchedEffect(imageUrl) {
            coroutineScope.launch {
                isDownloading = true
                val downloadedPath = try {
                    cacheManager.downloadImageToCache(imageUrl, isPreview)
                } catch (e: Exception) {
                    // Handle download error gracefully (e.g., log error, show placeholder image)
                    null
                }
                isDownloading = false
                if (downloadedPath != null) {
                    // Trigger recomposition to load the cached image
                }
            }
        }
        // Display light gray background while image is downloading
        if (isDownloading) {
            Box(
                modifier = modifier
                    .background(Color.LightGray)
                    .fillMaxSize()
            )
        }
    }
}

/**
 * inPreviewGrid: If the video is previewed in a Grid as part of tweet item in a list. The aspect
 *                ratio shall be 1:1, otherwise use the video's real aspectRatio
 * index: when there are multiple videos in a grid, the first one is played automatically.
 * **/
@Composable
fun VideoPreview(url: String, modifier: Modifier = Modifier, index: Int = -1, inPreviewGrid: Boolean = true) {
    val context = LocalContext.current
    val item = androidx.media3.common.MediaItem.fromUri(Uri.parse(url))
    val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(item)
        prepare()
        playWhenReady = false
//        playWhenReady = index==0      // wait until globalPosition is figured out.
    }
    var aspectRatio by remember { mutableFloatStateOf(1f) }
    if ( !inPreviewGrid ) {
         LaunchedEffect(url.substringAfterLast("/")) {
             val pair = getVideoDimensions(url) ?: Pair(400, 400)
             aspectRatio = pair.first.toFloat() / pair.second.toFloat()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { PlayerView(context).apply { player = exoPlayer } },
            modifier = modifier
                .aspectRatio(aspectRatio)
        )
        // Fullscreen button
        IconButton(
            onClick = {
                println("click to open full screen")    // full view will open
            },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Full screen",
                tint = Color.White,
                modifier = modifier.size(ButtonDefaults.IconSize)
            )
        }
    }
}
