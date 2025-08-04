package us.fireshare.tweet.tweet

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.AudioPreview
import us.fireshare.tweet.widget.FullScreenVideoPlayer
import us.fireshare.tweet.widget.ImageViewer
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.widget.VideoPreview
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment

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
    // State for full-screen video
    var showFullScreenVideo by remember { mutableStateOf(false) }
    var fullScreenVideoMid by remember { mutableStateOf<String?>(null) }
    val tweet by viewModel.tweetState.collectAsState()
    val attachments = mediaItems.map {
        val inferredType = inferMediaTypeFromAttachment(it)
        val mediaUrl = getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString()
        MediaItem(mediaUrl, inferredType)
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    /**
     * Action to take when any media item is clicked.
     * Audio files navigate to tweet detail page, others open in MediaBrowser for browsing with swipe navigation.
     * */
    val goto: (Int) -> Unit = { idx: Int ->
        val attachment = attachments[idx]

        // Audio files should navigate to tweet detail page
        when (attachment.type) {
            MediaType.Audio -> {
                navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
            }
            MediaType.Video -> {
                // Show full-screen video directly
                fullScreenVideoMid = mediaItems[idx].mid
                showFullScreenVideo = true
            }
            else -> {
                // Navigate to MediaBrowser for all other media types to enable swipe navigation
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
                Box(
                    modifier = backgroundModifier
                        .clipToBounds()
                        .clickable {
                            goto(index)
                        }
                ) {
                    AudioPreview(mediaItems, index, Modifier.fillMaxSize())
                }
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
    
    // Full-screen video overlay
    if (showFullScreenVideo && fullScreenVideoMid != null) {
        val videoMid = fullScreenVideoMid!!
        val mediaUrl = getMediaUrl(videoMid, tweet.author?.baseUrl.orEmpty()).toString()
        
        // Try to get existing player for seamless transition
        val existingPlayer = VideoManager.transferToFullScreen(videoMid)
        
        Dialog(
            onDismissRequest = { showFullScreenVideo = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                if (existingPlayer != null) {
                    // Use existing player for seamless transition
                    FullScreenVideoPlayer(
                        existingPlayer = existingPlayer,
                        videoMid = videoMid,
                        onClose = { showFullScreenVideo = false },
                        enableImmersiveMode = true
                    )
                } else {
                    // Fallback to regular full-screen player
                    FullScreenVideoPlayer(
                        videoUrl = mediaUrl,
                        onClose = { showFullScreenVideo = false },
                        autoPlay = true,
                        enableImmersiveMode = true,
                        autoReplay = true
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
    val request = DownloadManager.Request(url.toUri())
        .setTitle(fileName)
        .setDescription("Downloading")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    downloadManager.enqueue(request)

    Toast.makeText(context, "Downloading file...", Toast.LENGTH_SHORT).show()
}

