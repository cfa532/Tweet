package us.fireshare.tweet.widget

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import androidx.core.net.toUri
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl

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
    // Debug logging for mediaItems and limitedMediaList sizes
    Timber.d("MediaPreviewGrid - mediaItems.size: ${mediaItems.size}, limitedMediaList.size: ${limitedMediaList.size}")

    fun aspectRatioOf(item: MimeiFileType): Float {
        val itemType = inferMediaTypeFromAttachment(item)
        if (itemType == MediaType.Video || itemType == MediaType.Image) {
            return item.aspectRatio?.takeIf { it > 0 } ?: (1f)
        }
        // For other types, use square aspect ratio
        return 1f
    }

    // Track which video should autoplay (first video in the grid)
    val firstVideoIndex = remember {
        limitedMediaList.indexOfFirst { 
            inferMediaTypeFromAttachment(it) == MediaType.Video
        }.takeIf { it >= 0 } ?: -1
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
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

                if (allPortrait) {
                    Timber.d("MediaPreviewGrid - 3 items: allPortrait branch")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f), // square grid
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left half
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: right half, stacked vertically
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier.fillMaxSize(),
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
                    Timber.d("MediaPreviewGrid - 3 items: allLandscape branch")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top half
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        // Second and third: bottom half, side by side
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier.fillMaxSize(),
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
                    Timber.d("MediaPreviewGrid - 3 items: isPortrait0 fallback branch")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier.fillMaxSize(),
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
                    Timber.d("MediaPreviewGrid - 3 items: else (landscape fallback) branch")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = firstVideoIndex == 0,
                                inPreviewGrid = true,
                                viewModel = viewModel
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            for (idx in 1..2) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    MediaItemView(
                                        limitedMediaList,
                                        modifier = Modifier.fillMaxSize(),
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
                        .clip(RoundedCornerShape(8.dp))
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
        Timber.d("MediaPreview - MediaItem: mid=${it.mid}, type=$inferredType, url=$mediaUrl, extractedMid=$extractedMid")
        MediaItem(mediaUrl, inferredType)
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    // Add logging for debugging
    Timber.d("MediaItemView - index: $index, type: ${attachment.type}, url: ${attachment.url}")
    /**
     * Action to take when any media item is clicked.
     * All media types open in MediaBrowser for browsing with swipe navigation.
     * */
    val goto: (Int) -> Unit = { idx: Int ->
        Timber.d("MediaPreview - goto called for index: $idx, tweet.mid: ${tweet.mid}, tweet.authorId: ${tweet.authorId}")
        Timber.d("MediaPreview - attachments size: ${attachments.size}")
        // Navigate to MediaBrowser for all media types to enable swipe navigation
        try {
            navController.navigate(
                NavTweet.MediaViewer(MediaViewerParams(
                    attachments, idx, tweet.mid, tweet.authorId
                ))
            )
            Timber.d("MediaPreview - Navigation successful")
        } catch (e: Exception) {
            Timber.e("MediaPreview - Navigation failed: ${e.message}")
        }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        when (attachment.type) {
            MediaType.Image -> {
                // Use a Box with clickable modifier to handle image clicks
                Box(
                    modifier = modifier.clickable { 
                        Timber.d("MediaPreview - Image clicked at index: $index")
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
    if (attachment.aspectRatio != null) {
        return MediaType.Video
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
    
    Timber.d("createExoPlayer - Original URL: $url")
    Timber.d("createExoPlayer - Base URL: $baseUrl")
    Timber.d("createExoPlayer - Master URL: $masterUrl")
    Timber.d("createExoPlayer - Playlist URL: $playlistUrl")
    
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
                    Timber.e("createExoPlayer - Player error: ${error.message}")
                    Timber.e("createExoPlayer - Error cause: ${error.cause}")
                    Timber.e("createExoPlayer - Error code: ${error.errorCode}")
                    Timber.e("createExoPlayer - Has tried playlist: $hasTriedPlaylist")
                    Timber.e("createExoPlayer - Has tried original: $hasTriedOriginal")
                    
                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true
                        Timber.d("createExoPlayer - Trying fallback to playlist URL: $playlistUrl")
                        
                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else if (!hasTriedOriginal) {
                        hasTriedOriginal = true
                        Timber.d("createExoPlayer - Trying original URL as last resort: $url")
                        
                        // If both HLS attempts fail, try the original URL (progressive video)
                        val originalMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(url)
                        )
                        setMediaSource(originalMediaSource)
                        prepare()
                    } else {
                        Timber.e("createExoPlayer - All fallback attempts failed for URL: $url")
                        Timber.e("createExoPlayer - Video playback failed after trying HLS and original URL")
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
                    Timber.d("createExoPlayer - Playback state changed to: $stateName")
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Timber.d("createExoPlayer - Is playing changed to: $isPlaying")
                }
                
                override fun onIsLoadingChanged(isLoading: Boolean) {
                    Timber.d("createExoPlayer - Is loading changed to: $isLoading")
                }
            })
        }
    
    // Start with master.m3u8 (try HLS first)
    val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    player.setMediaSource(mediaSource)
    
    // Prepare the player immediately after setting up the listener
    Timber.d("createExoPlayer - Preparing ExoPlayer after creation")
    player.prepare()
    
    Timber.d("createExoPlayer - ExoPlayer created successfully")
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
