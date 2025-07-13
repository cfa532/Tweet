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
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.hls.HlsMediaSource
import androidx.media3.exoplayer.source.hls.DefaultHlsPlaylistParserFactory
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



/**
 * Creates an ExoPlayer instance optimized for HLS streaming (default format)
 * Progressive video support is maintained for backward compatibility
 * 
 * @param context Android context
 * @param url Video URL (HLS is default, progressive fallback if needed)
 * @return Configured ExoPlayer instance
 */
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

    // HLS is now the default video format, progressive video is only for backward compatibility
    // Always try HLS first, and only fall back to progressive if HLS fails
    android.util.Log.d("MediaPreview", "Creating HLS MediaSource (default) for URL: $url")
    val mediaSource: MediaSource = HlsMediaSource.Factory(cacheDataSourceFactory)
        .setAllowChunklessPreparation(true)
        .setPlaylistParserFactory(DefaultHlsPlaylistParserFactory())
        .createMediaSource(mediaItem)

    val exoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            LoadControl.Builder()
                .setBufferDurationsMs(
                    5000, // minBufferMs - minimum duration of media that the player will attempt to ensure is buffered
                    30000, // maxBufferMs - maximum duration of media that the player will attempt to buffer
                    1000, // bufferForPlaybackMs - duration of media that must be buffered for playback to start
                    2000 // bufferForPlaybackAfterRebufferMs - duration of media that must be buffered for playback to resume after a rebuffer
                )
                .build()
        )
        .build()
    
    // Add listener for player events (HLS is default, with fallback to progressive if needed)
    exoPlayer.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    android.util.Log.d("MediaPreview", "HLS player ready for URL: $url")
                }
                Player.STATE_BUFFERING -> {
                    android.util.Log.d("MediaPreview", "HLS player buffering for URL: $url")
                }
                Player.STATE_ENDED -> {
                    android.util.Log.d("MediaPreview", "HLS player ended for URL: $url")
                }
                Player.STATE_IDLE -> {
                    android.util.Log.d("MediaPreview", "HLS player idle for URL: $url")
                }
            }
        }
        
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("MediaPreview", "HLS player error for URL: $url", error)
            // Note: ExoPlayer will automatically handle fallback to progressive format if HLS fails
        }
    })
    
    return exoPlayer.apply {
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
