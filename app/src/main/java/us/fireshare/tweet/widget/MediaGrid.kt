package us.fireshare.tweet.widget

import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.MediaItemView
import us.fireshare.tweet.viewmodel.TweetViewModel

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(UnstableApi::class)
@Composable
fun MediaGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel
) {
    Timber.d("MediaPreviewGrid: Composable called with ${mediaItems.size} items")
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    
    // Optimize: Pre-compute derived values to avoid recalculation
    val maxItems by remember(mediaItems.size) {
        derivedStateOf {
            when (mediaItems.size) {
                1 -> 1
                2, 3 -> mediaItems.size
                else -> 4
            }
        }
    }
    
    val limitedMediaList by remember(mediaItems, maxItems) {
        derivedStateOf { mediaItems.take(maxItems) }
    }

    val gridState = rememberLazyGridState()

    // Optimize: Pre-compute aspect ratios once instead of on every recomposition
    // This function is NOT @Composable to avoid unnecessary recomposition checks
    fun aspectRatioOf(item: MimeiFileType): Float {
        val itemType = inferMediaTypeFromAttachment(item)
        return when (itemType) {
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // For videos, try to get aspect ratio from stored value first
                item.aspectRatio?.takeIf { it > 0 } ?: (4f / 3f) // Default to 4:3 to prevent shaking
            }
            MediaType.Image -> {
                // For images, use stored aspect ratio or default golden ratio
                item.aspectRatio?.takeIf { it > 0 } ?: 1.618f
            }
            else -> {
                // For other types, use golden ratio
                1.618f
            }
        }
    }
    
    // Optimize: Cache aspect ratios to avoid recalculation during recomposition
    val cachedAspectRatios by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.map { item -> aspectRatioOf(item) }
        }
    }

    val context = LocalContext.current
    
    // Track which video should autoplay (only the first video in the grid)
    val firstVideoIndex by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.indexOfFirst { 
                val mediaType = inferMediaTypeFromAttachment(it)
                mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
            }.takeIf { it >= 0 } ?: -1
        }
    }
    
    // Set up sequential playback for multiple videos
    val videoMids by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.mapIndexedNotNull { index, item ->
                val mediaType = inferMediaTypeFromAttachment(item)
                if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) item.mid else null
            }
        }
    }
    
    // Build mapping between video-only list and grid indices
    val videoGridIndices by remember(limitedMediaList) {
        derivedStateOf {
            limitedMediaList.mapIndexedNotNull { index, item ->
                val mediaType = inferMediaTypeFromAttachment(item)
                if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) index else null
            }
        }
    }

    // Track current playing video as index within videoGridIndices
    var currentVideoListIndex by remember { mutableStateOf(if (firstVideoIndex >= 0) 0 else -1) }

    fun isAutoPlayForGridIndex(gridIndex: Int): Boolean {
        return currentVideoListIndex >= 0 &&
            currentVideoListIndex < videoGridIndices.size &&
            videoGridIndices[currentVideoListIndex] == gridIndex
    }
    
    // Optimize: Use LaunchedEffect with proper keys to avoid unnecessary video setup
    LaunchedEffect(videoMids) {
        if (videoMids.size > 1) {
            // For multiple videos, only the first one should autoplay initially
            currentVideoListIndex = 0
            VideoManager.setupSequentialPlayback(videoMids)
        } else if (videoMids.size == 1) {
            // Single video - no sequential playback needed
            currentVideoListIndex = 0
            VideoManager.stopSequentialPlayback()
        } else {
            currentVideoListIndex = -1
            VideoManager.stopSequentialPlayback()
        }
        
        // Preload only visible videos to reduce memory pressure
        videoMids.forEachIndexed { index, videoMid ->
            val mediaItem = limitedMediaList.find { it.mid == videoMid }
            mediaItem?.let { item ->
                val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
                if (!VideoManager.isVideoPreloaded(videoMid)) {
                    // Use application scope to avoid blocking the UI thread
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            VideoManager.preloadVideo(context, videoMid, mediaUrl, item.type)
                        } catch (e: Exception) {
                            // Log error but don't block UI
                            Timber.tag("MediaGrid").e(e, "Failed to preload video: $videoMid")
                        }
                    }
                }
            }
        }
        
        // Preload images for faster loading
        val imageMids = limitedMediaList.mapNotNull { item ->
            val mediaType = inferMediaTypeFromAttachment(item)
            if (mediaType == MediaType.Image) item.mid else null
        }
        
        imageMids.forEach { imageMid ->
            val mediaItem = limitedMediaList.find { it.mid == imageMid }
            mediaItem?.let { item ->
                val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
                // Use application scope to avoid blocking the UI thread
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ImageCacheManager.preloadImages(context, imageMid, mediaUrl)
                    } catch (e: Exception) {
                        // Log error but don't block UI
                        Timber.tag("MediaGrid").e(e, "Failed to preload image: $imageMid")
                    }
                }
            }
        }
    }
    
    // Handle sequential video completion
    LaunchedEffect(currentVideoListIndex) {
        if (videoMids.size > 1 && currentVideoListIndex >= 0) {
            // Set up completion listener for current video
            val currentVideoMid = videoMids[currentVideoListIndex]
            currentVideoMid?.let { mid ->
                // Wait for video to complete and then move to next
                delay(100) // Small delay to ensure video is loaded
                // The actual completion handling will be done in VideoPreview
            }
        }
    }
    
    // Clean up sequential playback when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            VideoManager.stopSequentialPlayback()
        }
    }
    
    // Function to handle video completion and move to next
    fun onVideoCompleted(gridIndex: Int) {
        if (videoMids.isEmpty()) return
        // Translate grid index to video list index
        val completedVideoListIndex = videoGridIndices.indexOf(gridIndex)
        if (completedVideoListIndex == -1) return
        if (currentVideoListIndex == completedVideoListIndex) {
            // Notify VideoManager about completion
            val completedVideoMid = videoMids.getOrNull(completedVideoListIndex)
            completedVideoMid?.let { mid ->
                VideoManager.onVideoCompleted(mid)
            }

            // Advance to next video without looping; stop after last
            val nextListIndex = completedVideoListIndex + 1
            currentVideoListIndex = if (nextListIndex < videoGridIndices.size) nextListIndex else -1
            if (currentVideoListIndex == -1) {
                VideoManager.stopSequentialPlayback()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            when (limitedMediaList.size) {
            1 -> {
                // Use cached aspect ratio for better performance
                val aspectRatio = if (cachedAspectRatios[0] > 0.8f) {
                    cachedAspectRatios[0]
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
                    autoPlay = isAutoPlayForGridIndex(0),
                    inPreviewGrid = true,
                    loadOriginalImage = true, // Load original high-res image for single image
                    viewModel = viewModel,
                    onVideoCompleted = { onVideoCompleted(0) }
                )
            }
            2 -> {
                // Use cached aspect ratios for better performance
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
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
                                    autoPlay = isAutoPlayForGridIndex(idx),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(idx) }
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
                                    autoPlay = isAutoPlayForGridIndex(idx),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(idx) }
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
                                    autoPlay = isAutoPlayForGridIndex(0),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(0) }
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
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(1) }
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
                                    autoPlay = isAutoPlayForGridIndex(0),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(0) }
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
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    onVideoCompleted = { onVideoCompleted(1) }
                                )
                            }
                        }
                    }
                }
            }
            3 -> {
                // Use cached aspect ratios for better performance
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
                val ar2 = cachedAspectRatios[2]
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
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                onVideoCompleted = { onVideoCompleted(0) }
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
                                        autoPlay = isAutoPlayForGridIndex(idx),
                                        inPreviewGrid = true,
                                        viewModel = viewModel,
                                        onVideoCompleted = { onVideoCompleted(idx) }
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
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                onVideoCompleted = { onVideoCompleted(0) }
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
                                        autoPlay = isAutoPlayForGridIndex(idx),
                                        inPreviewGrid = true,
                                        viewModel = viewModel,
                                        onVideoCompleted = { onVideoCompleted(idx) }
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
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                onVideoCompleted = { onVideoCompleted(0) }
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
                                        autoPlay = isAutoPlayForGridIndex(idx),
                                        inPreviewGrid = true,
                                        viewModel = viewModel,
                                        onVideoCompleted = { onVideoCompleted(idx) }
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
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                onVideoCompleted = { onVideoCompleted(0) }
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
                                        autoPlay = isAutoPlayForGridIndex(idx),
                                        inPreviewGrid = true,
                                        viewModel = viewModel,
                                        onVideoCompleted = { onVideoCompleted(idx) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            4 -> {
                // Use improved lazy grid method for 4+ items with better loading optimization
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(
                        items = limitedMediaList,
                        key = { index, item -> "${item.mid}_$index" } // Stable key for better performance
                    ) { index, mediaItem ->
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
                            autoPlay = isAutoPlayForGridIndex(index),
                            inPreviewGrid = true,
                            viewModel = viewModel,
                            onVideoCompleted = { onVideoCompleted(index) }
                        )
                    }
                }
            }
            }
        }

        // Caption for single-video grid: title or file name (without extension)
        if (limitedMediaList.size == 1) {
            val singleItem = limitedMediaList[0]
            val singleItemType = inferMediaTypeFromAttachment(singleItem)
            if (singleItemType == MediaType.Video || singleItemType == MediaType.HLS_VIDEO) {
                // Prefer tweet title; fallback to file name without extension
                val rawTitle = tweet.title?.takeIf { it.isNotBlank() }
                val fileNameWithoutExt = singleItem.fileName
                    ?.substringBeforeLast('.', missingDelimiterValue = singleItem.fileName)
                    ?.takeIf { it.isNotBlank() }

                val captionText = rawTitle ?: fileNameWithoutExt

                if (!captionText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = captionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Infer media type from attachment properties when backend doesn't provide type
 */
fun inferMediaTypeFromAttachment(attachment: MimeiFileType): MediaType {
    // Check if type is provided and valid
    if (attachment.type != null && attachment.type != MediaType.Unknown) {
        return attachment.type
    }

    // If type is Unknown but we have a valid type, try to infer from other properties
    if (attachment.type == MediaType.Unknown) {
        // Check if it has aspect ratio (indicates video)
        if (attachment.aspectRatio != null && attachment.aspectRatio > 0) {
            return MediaType.Video
        }
    }

    // Check filename extension
    val fileName = attachment.fileName?.lowercase() ?: ""

    // Special case for .3gp files - check if they have aspect ratio (video) or not (audio)
    if (fileName.endsWith(".3gp")) {
        return if (attachment.aspectRatio != null && attachment.aspectRatio > 0) {
            MediaType.Video
        } else {
            MediaType.Audio
        }
    }

    val inferredType = when {
        fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png") || fileName.endsWith(".gif") ||
                fileName.endsWith(".webp") || fileName.endsWith(".bmp") -> MediaType.Image

        fileName.endsWith(".mp4") || fileName.endsWith(".mov") ||
                fileName.endsWith(".avi") || fileName.endsWith(".mkv") ||
                fileName.endsWith(".webm") || fileName.endsWith(".m4v") ||
                fileName.endsWith(".3gpp") -> MediaType.Video

        fileName.endsWith(".m3u8") -> MediaType.HLS_VIDEO

        fileName.endsWith(".mp3") || fileName.endsWith(".wav") ||
                fileName.endsWith(".aac") || fileName.endsWith(".ogg") ||
                fileName.endsWith(".flac") || fileName.endsWith(".m4a") ||
                fileName.endsWith(".wma") || fileName.endsWith(".opus") ||
                fileName.endsWith(".amr") -> MediaType.Audio

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
    return inferredType
}
