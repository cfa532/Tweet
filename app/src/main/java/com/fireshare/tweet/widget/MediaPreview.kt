package com.fireshare.tweet.widget

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.HproseInstance.preferenceHelper
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MediaItem
import com.fireshare.tweet.datamodel.MediaType
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.MediaViewerParams
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.Gadget.isElementVisible
import com.fireshare.tweet.widget.VideoCacheManager.getVideoDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun MediaPreviewGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel,
    containerWidth: Dp = 400.dp
) {    // need to check container width later
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    // check if all attachments are audio
    val isAllAudio = mediaItems.all { it.type == MediaType.Audio }
    val gridCells = if (isAllAudio) 1 else if (mediaItems.size > 1) 2 else 1
    val maxItems = if (isAllAudio) 9 else when (mediaItems.size) {
        1 -> 1
        in 2..3 -> 2
        else -> 4
    }
    val limitedMediaList = mediaItems.take(maxItems)

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCells),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        val modifier = if (gridCells == 1) Modifier.fillMaxWidth()
            else Modifier.size(containerWidth / gridCells)
        var isFirstVideo = false
        itemsIndexed(limitedMediaList) { index, mediaItem ->
            MediaItemView(
                limitedMediaList,
                modifier = modifier
                    .wrapContentSize()
                    .clickable {
                        val params = MediaViewerParams(
                            mediaItems.map {
                                MediaItem(
                                    getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                    it.type
                                )
                            }, index, tweet.mid, tweet.authorId
                        )
                        navController.navigate(NavTweet.MediaViewer(params))
                    },
                index = index,
                /**
                 * If the last item previewed is not the last of the attachments, show a plus sign
                 * to indicate there are more items hidden.
                 * */
                numOfHiddenItems = if (index == limitedMediaList.size - 1 && mediaItems.size > maxItems)
                    mediaItems.size - maxItems else 0,
                // autoplay first video item
                autoPlay = if (mediaItem.type == MediaType.Video && !isFirstVideo) {
                                isFirstVideo = true
                                true
                            } else {
                                false
                            },
                inPreviewGrid = true,
                viewModel
            )
        }
    }
}

@Composable
fun MediaItemView(
    mediaItems: List<MimeiFileType>,
    modifier: Modifier = Modifier,
    index: Int,
    numOfHiddenItems: Int = 0,      // add a PLUS sign to indicate more items not shown
    autoPlay: Boolean = false,      // autoplay first video item, index 0
    inPreviewGrid: Boolean = true,  // use real aspectRatio when not displaying in preview grid.
    viewModel: TweetViewModel
) {
    val tweet by viewModel.tweetState.collectAsState()
    val attachments = mediaItems.map {
        MediaItem(
            getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
            it.type
        )
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    /**
     * Action to take when the Full Screen button on video is clicked.
     * Image is opened in full screen automatically when clicked upon.
     * */
    val goto: (Int) -> Unit = { idx: Int ->
        navController.navigate(
            NavTweet.MediaViewer(MediaViewerParams(
                attachments, idx, tweet.mid, tweet.authorId
            ) )
        )
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        when (attachment.type) {
            MediaType.Image -> {
                ImageViewer(attachment.url, modifier)
            }
            MediaType.Video -> {
                VideoPreview(
                    attachment.url,
                    modifier,
                    index,
                    autoPlay,
                    inPreviewGrid,
                    mediaItems[index].aspectRatio,
                ) {
                    goto(index)
                }
            }
            MediaType.Audio -> {
                val backgroundModifier = if (index % 2 != 0) { // Check if index is odd
                    modifier.background(Color.Black.copy(alpha = 0.05f)) // Slightly darker background
                } else {
                    modifier
                }
                AudioPreview(mediaItems, index, backgroundModifier, tweet)
            }
            else -> {       // add link to download other file type
                BlobLink(mediaItems[index], attachment.url, modifier)
            }
        }
        if (numOfHiddenItems > 0) {
            /**
             * Show a PLUS sign and number to indicate more items not shown
             * */
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x40FFFFFF)), // Lighter shaded background
                contentAlignment = Alignment.Center
            ) {
                Row(modifier = Modifier.align(Alignment.Center))
                {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(50.dp)
                            .alpha(0.8f)
                    )
                    Text(
                        text = numOfHiddenItems.toString(),
                        color = Color.White,
                        fontSize = 50.sp, // Adjust this value as needed
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .alpha(0.8f)
                    )
                }
            }
        }
    }
}

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
    var videoRatio by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(url.getMimeiKeyFromUrl()) {
        val (width, height) = getVideoDimensions(url) ?: Pair(400, 400)
        videoRatio = width.toFloat() / height.toFloat()
        if (inPreviewGrid) {
            videoRatio = max(1f, videoRatio)
        }
    }
    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    Box(
        modifier = modifier
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
                }
            },
            modifier = modifier.aspectRatio(aspectRatio ?: videoRatio)
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

@Composable
fun BlobLink(
    blobItem: MimeiFileType,
    url: String,
    modifier: Modifier
) {
    val annotatedText = buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                color = Color.Cyan,
                textDecoration = TextDecoration.Underline
            )
        ) {
            append(blobItem.fileName.toString())
        }
        addStringAnnotation(
            tag = "URL",
            annotation = url,
            start = 0,
            end = blobItem.fileName.toString().length
        )
    }

    val context = LocalContext.current
    Text(
        text = annotatedText,
        modifier = modifier.fillMaxWidth()
            .padding(start = 4.dp)
            .wrapContentWidth(Alignment.Start)
            .clickable {
                downloadFile(context, url, blobItem.fileName.toString())
            }
    )
}

fun downloadFile(context: Context, url: String, fileName: String) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(fileName)
        .setDescription("Downloading")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)

    Toast.makeText(context, "Downloading file...", Toast.LENGTH_SHORT).show()
}

@OptIn(UnstableApi::class)
fun createExoPlayer(context: Context, url: String): ExoPlayer {
    val cache = VideoCacheManager.getCache(context)
    val dataSourceFactory = DefaultDataSource.Factory(context)
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val cacheKey = url.getMimeiKeyFromUrl()
    val mediaItem = androidx.media3.common.MediaItem.Builder()
        .setUri(Uri.parse(url))
        .setCustomCacheKey(cacheKey)  // This ensures the cache uses your unique key
        .build()

    val mediaSource: MediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
        .createMediaSource(mediaItem)

    return ExoPlayer.Builder(context).build().apply {
        setMediaSource(mediaSource)
        prepare()  // Prepares the player with the media source
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AudioPreview(
    mediaItems: List<MimeiFileType>,
    index: Int,
    modifier: Modifier = Modifier,
    tweet: Tweet,
) {
    val navController = LocalNavController.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp)
                .clickable {
                    navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.btn_play),
                contentDescription = "Play",
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = mediaItems[index].fileName ?: mediaItems[index].mid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    val cacheManager = remember { CacheManager(context) }
    val cachedPath = rememberUpdatedState(cacheManager.getCachedImagePath(imageUrl, isPreview))

    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Check if image is already cached. Use it directly if so.
    val cachedImage = remember(imageUrl.getMimeiKeyFromUrl()) {
        mutableStateOf(cacheManager.loadImageFromCache(cachedPath.value))
    }
    val adjustedModifier = if (isPreview) {
        modifier.fillMaxSize()      // image is viewed within a parent composable
    } else {
        modifier.fillMaxWidth()     // image is viewed full screen
    }

    LaunchedEffect(imageUrl) {
        if (cachedImage.value == null) {
            downloadScope.launch {
                isDownloading = true
                downloadError = false
                val downloadedPath =
                    cacheManager.downloadImageToCache(imageUrl, isPreview, imageSize)

                isDownloading = false
                if (downloadedPath != null) {
                    cachedImage.value = cacheManager.loadImageFromCache(downloadedPath)
                } else {
                    downloadError = true
                }
            }
        }
    }

    if (cachedImage.value != null) {
        Box(modifier = modifier) {
            Image(
                painter = BitmapPainter(cachedImage.value!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = adjustedModifier
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Wait for the first down event
                            val down = awaitFirstDown()

                            // Variable to track if a long press was detected
                            var longPressDetected = false

                            // Check for a long press
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                // Long press detected
                                longPressDetected = true
                                showMenu = true
                                menuPosition = longPress.position
                            }

                            // Wait for the up event or cancellation
                            // If the gesture is released before a long press is detected
                            if (waitForUpOrCancellation() != null && !longPressDetected) {
                                // Allow the event to propagate up for single click handling
                                // Do not consume the event here
                            }
                        }
                    }
            )
            if (showMenu) {
                // Calculate DropdownMenu position
                var parentSize by remember { mutableStateOf(IntSize.Zero) }
                val density = LocalDensity.current

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            parentSize = coordinates.size
                        }
                        .wrapContentWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    offset = DpOffset(
                        with(density) { menuPosition.x.toDp() },
                        with(density) { menuPosition.y.toDp() }
                    )
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Download",
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            showMenu = false
                            downloadScope.launch {
                                downloadImage(context, imageUrl)
                            }
                        },
                        modifier = Modifier.heightIn(max = 30.dp)
                    )
                }
            }
        }
    } else if (isDownloading || downloadError) { // Combined conditions
        Box(
            modifier = adjustedModifier
                .fillMaxWidth()
                .background(Color.LightGray) // Gray background
        ) {
            if (!isPreview) {
                if (cacheManager.isCached(imageUrl, true)) {
                    val placeholderImage = cacheManager.loadImageFromCache(
                        cacheManager.getCachedImagePath(imageUrl, true)
                    )
                    if (placeholderImage != null) {
                        Image(
                            painter = BitmapPainter(placeholderImage),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = adjustedModifier
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .heightIn(min = 400.dp)
                    )
                }
            }
        }
    } else {
        // Display placeholder for non-existent resource
        Box(
            modifier = adjustedModifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (cacheManager.isCached(imageUrl, true)) {
                val placeholderImage = cacheManager.loadImageFromCache(
                    cacheManager.getCachedImagePath(imageUrl, true)
                )
                if (placeholderImage != null) {
                    Image(
                        painter = BitmapPainter(placeholderImage),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = adjustedModifier
                    )
                }
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_user_avatar),
                    contentDescription = "Placeholder Avatar",
                    modifier = modifier
                        .size(imageSize.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}
