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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                autoPlay = if ((mediaItem.type ?: inferMediaTypeFromAttachment(mediaItem)) == MediaType.Video && !isFirstVideo) {
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
        val inferredType = it.type
        MediaItem(
            getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
            inferredType
        )
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    // Add logging for debugging
    Timber.d("MediaItemView - index: $index, type: ${attachment.type}, url: ${attachment.url}")
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
 * Creates an ExoPlayer instance that handles HLS videos properly
 * 
 * @param context Android context
 * @param url Video URL (points to directory containing HLS files)
 * @param mediaType Optional MediaType (not used in simplified approach)
 * @return Configured ExoPlayer instance
 */
@OptIn(UnstableApi::class)
fun createExoPlayer(context: Context, url: String, mediaType: MediaType? = null): ExoPlayer {
    val dataSourceFactory = DefaultDataSource.Factory(context)
    
    // For HLS videos, the URL points to a directory containing the manifest file
    // Try master.m3u8 first (multiple quality streams), then fall back to playlist.m3u8 (single resolution)
    val baseUrl = if (url.endsWith("/")) url else "$url/"
    val masterUrl = "${baseUrl}master.m3u8"
    val playlistUrl = "${baseUrl}playlist.m3u8"
    
    // Log the URLs being accessed
    Timber.d("VideoPreview - Original URL: $url")
    Timber.d("VideoPreview - Base URL: $baseUrl")
    Timber.d("VideoPreview - Master URL: $masterUrl")
    Timber.d("VideoPreview - Playlist URL: $playlistUrl")
    
    // Use DefaultMediaSourceFactory which automatically handles HLS and progressive
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    
    // Start with master.m3u8 (multiple quality streams)
    val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    
    val player = ExoPlayer.Builder(context)
        .build()
        .apply {
            setMediaSource(mediaSource)
            
            // Add error listener to fallback to playlist.m3u8 if master.m3u8 fails
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.d("VideoPreview - Master URL failed, trying playlist URL: $playlistUrl")
                    // If master.m3u8 fails, try playlist.m3u8
                    val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                        androidx.media3.common.MediaItem.fromUri(playlistUrl)
                    )
                    setMediaSource(fallbackMediaSource)
                    prepare()
                }
            })
        }
    
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
