package us.fireshare.tweet.tweet

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.AdvancedImageViewer
import us.fireshare.tweet.widget.AudioPreview
import us.fireshare.tweet.widget.ImageViewer
import us.fireshare.tweet.widget.VideoPreview
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun MediaItemView(
    mediaItems: List<MimeiFileType>,
    modifier: Modifier = Modifier,
    index: Int,
    numOfHiddenItems: Int = 0,      // add a PLUS sign to indicate more items not shown
    autoPlay: Boolean = false,      // autoplay first video item, index 0
    inPreviewGrid: Boolean = true,  // use real aspectRatio when not displaying in preview grid.
    loadOriginalImage: Boolean = false, // load original high-res image instead of compressed preview
    viewModel: TweetViewModel,
    onVideoCompleted: (() -> Unit)? = null,
    useIndependentVideoMute: Boolean = false, // For TweetDetailView - videos independent of global mute
    enableTapToShowControls: Boolean = false, // For TweetDetailView - enable tap-to-show controls
    allMediaItems: List<MimeiFileType>? = null // All media items for full screen navigation (if different from mediaItems)
) {
    // State for full-screen image
    var showFullScreenImage by remember { mutableStateOf(false) }
    var fullScreenImageMid by remember { mutableStateOf<String?>(null) }
    var fullScreenBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val tweet by viewModel.tweetState.collectAsState()
    val attachments = mediaItems.map {
        val inferredType = inferMediaTypeFromAttachment(it)
        val mediaUrl = getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString()
        MediaItem(mediaUrl, inferredType)
    }
    val attachment = attachments[index]
    val navController = LocalNavController.current
    val context = LocalContext.current
    /**
     * Action to take when any media item is clicked.
     * Images and videos open in full-screen mode, audio files navigate to tweet detail page, 
     * other files open with appropriate apps or download.
     * */
    val goto: (Int) -> Unit = { idx: Int ->
        val attachment = attachments[idx]

        // Handle different media types
        when (attachment.type) {
            MediaType.Image -> {
                // Show full-screen image directly
                fullScreenImageMid = mediaItems[idx].mid
                fullScreenBitmap = currentBitmap // Pass the current bitmap to fullscreen
                showFullScreenImage = true
            }
            MediaType.Audio -> {
                navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
            }
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // Navigate to MediaViewer for full-screen video (same as TweetDetailView)
                val params = MediaViewerParams(
                    mediaItems.map {
                        MediaItem(
                            getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                            it.type
                        )
                    }, idx, tweet.mid, tweet.authorId
                )
                navController.navigate(NavTweet.MediaViewer(params))
            }
            else -> {
                // Open other media types with appropriate apps
                try {
                    val mediaUrl = getMediaUrl(mediaItems[idx].mid, tweet.author?.baseUrl.orEmpty()).toString()
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = mediaUrl.toUri()
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    // Check if there's an app to handle this file type
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        // Fallback: try to download the file
                        downloadFile(context, mediaUrl, mediaItems[idx].fileName.toString())
                    }
                } catch (e: Exception) {
                    Timber.tag("MediaItemView").e("Failed to open file: ${e.message}")
                    // Fallback: try to download the file
                    try {
                        val mediaUrl = getMediaUrl(mediaItems[idx].mid, tweet.author?.baseUrl.orEmpty()).toString()
                        downloadFile(context, mediaUrl, mediaItems[idx].fileName.toString())
                    } catch (downloadException: Exception) {
                        Timber.tag("MediaItemView").e("Failed to download file: ${downloadException.message}")
                    }
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
            // Log only when processing video attachments
    if (attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO) {
        Timber.d("MediaItemView: Processing video attachment for index: $index")
    }
        when (attachment.type) {
            MediaType.Image -> {
                // Track visibility for priority-based loading
                var isVisible by remember { mutableStateOf(true) } // Default to true since images in feed are likely visible
                
                // Use a Box with clickable modifier to handle image clicks
                // Ensure the inner Box fills the parent to properly display the image
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .onGloballyPositioned { layoutCoordinates ->
                            // Update visibility based on actual layout - if item has size, it's visible
                            val hasSize = layoutCoordinates.size.width > 0 && layoutCoordinates.size.height > 0
                            isVisible = hasSize
                        }
                        .clickable {
                            goto(index)
                        }
                ) {
                    ImageViewer(
                        attachment.url,
                        modifier = Modifier.fillMaxSize(), // Always fill parent in preview grid
                        enableLongPress = false, // Disable long press to allow clickable to work
                        inPreviewGrid = inPreviewGrid,
                        isVisible = isVisible,
                        loadOriginalImage = loadOriginalImage,
                        onBitmapLoaded = { bitmap ->
                            currentBitmap = bitmap
                            Timber.tag("MediaItemView").d("Bitmap loaded for image at index $index: ${bitmap?.width}x${bitmap?.height}")
                        }
                    )
                }
            }
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // Use a completely stable approach with key
                val videoMid = mediaItems[index].mid
                val videoUrl = attachment.url

                // Use key with a stable identifier to prevent recreation
                key("video_${videoMid}_${index}") {
                    VideoPreview(
                        url = videoUrl,
                        modifier = modifier,
                        index = index,
                        autoPlay = autoPlay,
                        inPreviewGrid = inPreviewGrid,
                        callback = { goto(index) },
                        videoMid = videoMid,
                        videoType = attachment.type,
                        onVideoCompleted = onVideoCompleted,
                        useIndependentMuteState = useIndependentVideoMute,
                        enableTapToShowControls = enableTapToShowControls
                    )
                }
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
                Timber.w("MediaItemView: Falling through to BlobLink for type: ${attachment.type}")
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
    

    
    // Full-screen image overlay
    if (showFullScreenImage && fullScreenImageMid != null) {
        val imageMid = fullScreenImageMid!!
        
        // Use allMediaItems if provided (for full navigation), otherwise use mediaItems
        val itemsForNavigation = allMediaItems ?: mediaItems
        
        // Filter to only image attachments and get their URLs
        val imageAttachments = itemsForNavigation.mapIndexedNotNull { idx, item ->
            val inferredType = inferMediaTypeFromAttachment(item)
            if (inferredType == MediaType.Image) {
                idx to getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
            } else {
                null
            }
        }
        
        // Find current image index in the filtered list
        val currentImageIndexInList = imageAttachments.indexOfFirst { (idx, _) ->
            itemsForNavigation[idx].mid == imageMid
        }
        
        // Get current media URL based on current imageMid
        val mediaUrl = if (currentImageIndexInList >= 0) {
            imageAttachments[currentImageIndexInList].second
        } else {
            getMediaUrl(imageMid, tweet.author?.baseUrl.orEmpty()).toString()
        }
        
        // Get list of image URLs
        val imageUrls = imageAttachments.map { (_, url) -> url }
        
        Dialog(
            onDismissRequest = { },
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
                // Use key to force recomposition when image changes - this ensures smooth animation
                key(imageMid) {
                    AdvancedImageViewer(
                        imageUrl = mediaUrl,
                        enableLongPress = true,
                        initialBitmap = null, // Always start fresh for smooth animation
                        onClose = { showFullScreenImage = false },
                        modifier = Modifier.fillMaxSize(),
                        imageUrls = if (imageUrls.size > 1) imageUrls else null,
                        currentImageIndex = currentImageIndexInList.coerceAtLeast(0),
                        onNextImage = {
                            if (currentImageIndexInList >= 0 && currentImageIndexInList < imageAttachments.size - 1) {
                                // Load next image - the key(imageMid) will trigger recomposition and animation
                                val nextIndex = currentImageIndexInList + 1
                                val (nextMediaIndex, _) = imageAttachments[nextIndex]
                                fullScreenImageMid = itemsForNavigation[nextMediaIndex].mid
                                fullScreenBitmap = null // Reset bitmap to load new image
                            }
                        }
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

    Toast.makeText(context, context.getString(R.string.downloading_file), Toast.LENGTH_SHORT).show()
}

