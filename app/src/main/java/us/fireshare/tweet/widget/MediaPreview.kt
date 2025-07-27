package us.fireshare.tweet.widget

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.SimplifiedVideoCacheManager
import androidx.compose.runtime.derivedStateOf

@OptIn(UnstableApi::class)
@Composable
fun MediaPreviewGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel,
) {
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    val maxItems = when (mediaItems.size) {
        1 -> 1
        2, 3 -> mediaItems.size
        else -> 4
    }
    val limitedMediaList = mediaItems.take(maxItems)

    // Helper: get aspect ratio for an item, using Compose state for images
    @OptIn(UnstableApi::class)
    @Composable
    fun aspectRatioOf(item: MimeiFileType): Float {
        val itemType = inferMediaTypeFromAttachment(item)
        val context = LocalContext.current // Get context in composable scope
        if (itemType == MediaType.Video) {
            // For videos, try to get aspect ratio from stored value first
            val storedAspectRatio = item.aspectRatio?.takeIf { it > 0 }
            if (storedAspectRatio != null) {
                return storedAspectRatio
            }
            
            // If stored aspect ratio is not available, use a stable default
            // The actual aspect ratio will be calculated when the video is loaded
            return 4f / 3f // Default to 4:3 to prevent shaking
        }
        if (itemType == MediaType.Image) {
            // Use a stable approach that doesn't cause recomposition issues
            // First try to use the stored aspect ratio, then fallback to default
            val storedAspectRatio = item.aspectRatio?.takeIf { it > 0 }
            if (storedAspectRatio != null) {
                return storedAspectRatio
            }
            
            // If no stored aspect ratio, use a default square ratio to prevent shaking
            // The actual aspect ratio will be calculated when the image is loaded
            return 1.618f
        }
        // For other types, use square aspect ratio
        return 1.618f
    }

    // Track which video should autoplay (only the first video in the grid)
    val firstVideoIndex = remember {
        limitedMediaList.indexOfFirst { 
            inferMediaTypeFromAttachment(it) == MediaType.Video
        }.takeIf { it >= 0 } ?: -1
    }
    
    val context = LocalContext.current
    
    // Set up sequential playback for multiple videos
    val videoMids = remember {
        limitedMediaList.mapIndexedNotNull { index, item ->
            if (inferMediaTypeFromAttachment(item) == MediaType.Video) item.mid else null
        }
    }
    
    LaunchedEffect(videoMids) {
        if (videoMids.size > 1) {
            VideoManager.setupSequentialPlayback(videoMids)
        } else if (videoMids.size == 1) {
            // Single video - no sequential playback needed
            VideoManager.stopSequentialPlayback()
        }
        
        // Preload all videos in the grid
        videoMids.forEach { videoMid ->
            val mediaItem = limitedMediaList.find { it.mid == videoMid }
            mediaItem?.let { item ->
                val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
                if (!VideoManager.isVideoPreloaded(videoMid)) {
                    VideoManager.preloadVideo(context, videoMid, mediaUrl)
                }
            }
        }
    }
    
    // Clean up sequential playback when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            VideoManager.stopSequentialPlayback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
    ) {
        when (limitedMediaList.size) {
            1 -> {
                val aspectRatio = if (aspectRatioOf(limitedMediaList[0]) > 0.8f) {
                    aspectRatioOf(limitedMediaList[0])
                } else {
                    0.8f
                }
                MediaItemView(
                    limitedMediaList,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clipToBounds()
                        .clickable {
                            val params = MediaViewerParams(
                                mediaItems.map {
                                    MediaItem(
                                        getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                        it.type
                                    )
                                }, 0, tweet.mid, tweet.authorId
                            )
                            navController.navigate(NavTweet.MediaViewer(params))
                        },
                    index = 0,
                    numOfHiddenItems = if (mediaItems.size > maxItems) mediaItems.size - maxItems else 0,
                    autoPlay = firstVideoIndex == 0,
                    inPreviewGrid = true,
                    viewModel = viewModel
                )
            }
            2 -> {
                val ar0 = aspectRatioOf(limitedMediaList[0])
                val ar1 = aspectRatioOf(limitedMediaList[1])
                val isPortrait0 = ar0 < 1f
                val isPortrait1 = ar1 < 1f
                val isLandscape0 = ar0 > 1f
                val isLandscape1 = ar1 > 1f
                
                if (isLandscape0 && isLandscape1) {
                    // Both landscape: set grid's aspectRatio to 0.8 and align items vertically
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for (idx in 0..1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = idx,
                                    autoPlay = firstVideoIndex == idx,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                } else if (isPortrait0 && isPortrait1) {
                    // Both portrait: set grid's aspectRatio to 1 and align them horizontally
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        for (idx in 0..1) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = idx,
                                    autoPlay = firstVideoIndex == idx,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                } else {
                    // Mixed orientations: set grid's ratio to 4:3 and let landscape item takes wider space
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f/3f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        if (isPortrait0) {
                            // First is portrait, second is landscape
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = 0,
                                    autoPlay = firstVideoIndex == 0,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(2f)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = 1,
                                    autoPlay = firstVideoIndex == 1,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                        } else {
                            // First is landscape, second is portrait
                            Box(
                                modifier = Modifier
                                    .weight(2f)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = 0,
                                    autoPlay = firstVideoIndex == 0,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    index = 1,
                                    autoPlay = firstVideoIndex == 1,
                                    inPreviewGrid = true,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
            3 -> {
                val ar0 = aspectRatioOf(limitedMediaList[0])
                val ar1 = aspectRatioOf(limitedMediaList[1])
                val ar2 = aspectRatioOf(limitedMediaList[2])
                val allPortrait = ar0 < 1f && ar1 < 1f && ar2 < 1f
                val allLandscape = ar0 > 1f && ar1 > 1f && ar2 > 1f
                val isPortrait0 = ar0 < 1f
                val isPortrait1 = ar1 < 1f
                val isPortrait2 = ar2 < 1f
                val goldenRatio = 0.618f
                val remainder = 1f - goldenRatio

                if (allPortrait) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8%
                        Box(
                            modifier = Modifier
                                .weight(goldenRatio)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                    
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: right 38.2%, stacked vertically
                        Column(
                            modifier = Modifier.weight(remainder),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clipToBounds()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier
                                            .fillMaxSize(),
                                            
                                        index = idx,
                                        autoPlay = firstVideoIndex == idx,
                                        inPreviewGrid = true,
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                } else if (allLandscape) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8%
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(goldenRatio)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                    
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: bottom 38.2%, side by side
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(remainder),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clipToBounds()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier
                                            .fillMaxSize(),
                                            
                                        index = idx,
                                        autoPlay = firstVideoIndex == idx,
                                        inPreviewGrid = true,
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                } else if (isPortrait0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8%
                        Box(
                            modifier = Modifier
                                .weight(goldenRatio)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: right 38.2%, stacked vertically
                        Column(
                            modifier = Modifier.weight(remainder),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clipToBounds()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier
                                            .fillMaxSize(),
                                            
                                        index = idx,
                                        autoPlay = firstVideoIndex == idx,
                                        inPreviewGrid = true,
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8%
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(goldenRatio)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier
                                    .fillMaxSize(),
                                    
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: bottom 38.2%, side by side
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(remainder),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clipToBounds()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier
                                            .fillMaxSize(),
                                            
                                        index = idx,
                                        autoPlay = firstVideoIndex == idx,
                                        inPreviewGrid = true,
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }
            4 -> {
                // Use original grid method for 4 items
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(limitedMediaList) { index, mediaItem ->
                        MediaItemView(
                            limitedMediaList,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clipToBounds()
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
                            numOfHiddenItems = if (index == limitedMediaList.size - 1 && mediaItems.size > maxItems)
                                mediaItems.size - maxItems else 0,
                            autoPlay = firstVideoIndex == index,
                            inPreviewGrid = true,
                            viewModel = viewModel
                        )
                    }
                }
            }
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
        val inferredType = inferMediaTypeFromAttachment(it)
        val mediaUrl = getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString()
        val extractedMid = mediaUrl.getMimeiKeyFromUrl()
        MediaItem(mediaUrl, inferredType)
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    /**
     * Action to take when any media item is clicked.
     * All media types open in MediaBrowser for browsing with swipe navigation.
     * */
    val goto: (Int) -> Unit = { idx: Int ->
        // Navigate to MediaBrowser for all media types to enable swipe navigation
        try {
            navController.navigate(
                NavTweet.MediaViewer(MediaViewerParams(
                    attachments, idx, tweet.mid, tweet.authorId
                ))
            )
        } catch (e: Exception) {
            Timber.tag("MediaItemView").e("Navigation failed: ${e.message}")
        }
    }

    Box(
        modifier = modifier
            .background(Color.Gray.copy(alpha = 0.1f))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        when (attachment.type) {
            MediaType.Image -> {
                // Use a Box with clickable modifier to handle image clicks
                Box(
                    modifier = modifier
                        .clipToBounds()
                        .clickable { 
                            goto(index) 
                        }
                ) {
                    ImageViewer(
                        attachment.url, 
                        modifier = Modifier.fillMaxSize(),
                        enableLongPress = false // Disable long press to allow clickable to work
                    )
                }
            }
            MediaType.Video -> {
                VideoPreview(
                    url = attachment.url,
                    modifier = modifier,
                    index = index,
                    autoPlay = autoPlay,
                    inPreviewGrid = inPreviewGrid,
                    aspectRatio = mediaItems[index].aspectRatio,
                    callback = { goto(index) },
                    videoMid = mediaItems[index].mid
                )
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

/**
 * Infer media type from attachment properties when backend doesn't provide type
 */
fun inferMediaTypeFromAttachment(attachment: MimeiFileType): MediaType {
    // Check if aspectRatio is present (indicates video)
    if (attachment.type != null && attachment.type != MediaType.Unknown) {
        return attachment.type
    }
    
    // Check filename extension
    val fileName = attachment.fileName?.lowercase() ?: ""
    return when {
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || 
        fileName.endsWith(".png") || fileName.endsWith(".gif") || 
        fileName.endsWith(".webp") || fileName.endsWith(".bmp") -> MediaType.Image
        
        fileName.endsWith(".mp4") || fileName.endsWith(".mov") || 
        fileName.endsWith(".avi") || fileName.endsWith(".mkv") || 
        fileName.endsWith(".webm") || fileName.endsWith(".m3u8") -> MediaType.Video
        
        fileName.endsWith(".mp3") || fileName.endsWith(".wav") || 
        fileName.endsWith(".aac") || fileName.endsWith(".ogg") || 
        fileName.endsWith(".flac") -> MediaType.Audio
        
        fileName.endsWith(".pdf") -> MediaType.PDF
        fileName.endsWith(".doc") || fileName.endsWith(".docx") -> MediaType.Word
        fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> MediaType.Excel
        fileName.endsWith(".ppt") || fileName.endsWith(".pptx") -> MediaType.PPT
        fileName.endsWith(".zip") || fileName.endsWith(".rar") || 
        fileName.endsWith(".7z") -> MediaType.Zip
        fileName.endsWith(".txt") -> MediaType.Txt
        fileName.endsWith(".html") || fileName.endsWith(".htm") -> MediaType.Html
        
        else -> MediaType.Unknown
    }
}

fun downloadFile(context: Context, url: String, fileName: String) {
    val request = DownloadManager.Request(url.toUri())
        .setTitle(fileName)
        .setDescription("Downloading")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)

    Toast.makeText(context, "Downloading file...", Toast.LENGTH_SHORT).show()
}

/**
 * Creates an ExoPlayer instance that handles video data blobs with HLS fallback
 * 
 * @param context Android context
 * @param url Video URL (data blob)
 * @param mediaType Optional MediaType (not used in this system)
 * @return Configured ExoPlayer instance
 */
@OptIn(UnstableApi::class)
fun createExoPlayer(context: Context, url: String, mediaType: MediaType? = null): ExoPlayer {
    val dataSourceFactory = DefaultDataSource.Factory(context)
    
    // For data blobs, try HLS first, then fallback to original URL
    val baseUrl = if (url.endsWith("/")) url else "$url/"
    val masterUrl = "${baseUrl}master.m3u8"
    val playlistUrl = "${baseUrl}playlist.m3u8"
    
    // Use DefaultMediaSourceFactory which automatically handles HLS and progressive
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    
    val player = ExoPlayer.Builder(context)
        .build()
        .apply {
            // Add comprehensive listener for debugging and fallback
            addListener(object : androidx.media3.common.Player.Listener {
                private var hasTriedPlaylist = false
                private var hasTriedOriginal = false
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.tag("createExoPlayer").e("Player error: ${error.message}")
                    
                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true
                        
                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else if (!hasTriedOriginal) {
                        hasTriedOriginal = true
                        
                        // If both HLS attempts fail, try the original URL (progressive video)
                        val originalMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(url)
                        )
                        setMediaSource(originalMediaSource)
                        prepare()
                    } else {
                        Timber.e("createExoPlayer - All fallback attempts failed for URL: $url")
                    }
                }
                
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateName = when (playbackState) {
                        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                        androidx.media3.common.Player.STATE_READY -> "READY"
                        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN"
                    }
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                }
                
                override fun onIsLoadingChanged(isLoading: Boolean) {
                }
            })
        }
    
    // Start with master.m3u8 (try HLS first)
    val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    player.setMediaSource(mediaSource)
    
    // Prepare the player immediately after setting up the listener
    player.prepare()
    return player
}

// DefaultMediaSourceFactory automatically handles HLS and progressive videos

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
