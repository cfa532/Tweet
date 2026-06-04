package us.fireshare.tweet.widget

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.MediaItemView
import us.fireshare.tweet.viewmodel.TweetViewModel

/**
 * MediaGrid displays a grid of media items with intelligent layout based on aspect ratios.
 * 
 * Layout Strategy (matching iOS implementation):
 * - Uses PROPORTIONAL SIZING based on individual media aspect ratios
 * - Ensures maximum content visibility without excessive cropping
 * - Hero images in 3-item grids use golden ratio (61.8%) for aesthetic balance
 * - Clamps grid aspect ratios between 0.8 (portrait) and 1.618 (landscape/golden ratio)
 * 
 * 1 Item: Uses individual aspect ratio (min 0.8)
 * 2 Items: 
 *   - Both portrait: Horizontal layout with proportional widths
 *   - Both landscape: Vertical layout with proportional heights
 *   - Mixed: Horizontal layout with dynamic proportional widths
 * 3 Items:
 *   - All portrait: Hero left (61.8%), two stacked right with proportional heights
 *   - All landscape: Hero top (61.8%), two side-by-side bottom with proportional widths
 *   - Mixed: Hero (61.8% minimum) adapts based on first item orientation with proportional sizing
 * 4+ Items: 2x2 grid with aspect ratio based on all items' orientation
 */
@RequiresApi(Build.VERSION_CODES.R)
@OptIn(UnstableApi::class)
@Composable
fun MediaGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel,
    parentTweetId: MimeiId? = null,
    enableCoordinator: Boolean = true,
    containerTopY: Float? = null,
    enableRowPreloading: Boolean = true
) {
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    val videoCoordinator = LocalVideoCoordinator.current
    val audioMediaList by remember(mediaItems) {
        derivedStateOf {
            mediaItems.filter { inferMediaTypeFromAttachment(it) == MediaType.Audio }
        }
    }
    val visualMediaList by remember(mediaItems) {
        derivedStateOf {
            mediaItems.filter {
                when (inferMediaTypeFromAttachment(it)) {
                    MediaType.Image, MediaType.Video, MediaType.HLS_VIDEO -> true
                    else -> false
                }
            }
        }
    }
    val documentMediaList by remember(mediaItems) {
        derivedStateOf {
            mediaItems.filter {
                when (inferMediaTypeFromAttachment(it)) {
                    MediaType.PDF, MediaType.Word, MediaType.Excel, MediaType.PPT,
                    MediaType.Zip, MediaType.Txt, MediaType.Html, MediaType.Unknown -> true
                    else -> false
                }
            }
        }
    }
    // Optimize: Pre-compute derived values to avoid recalculation
    val maxItems by remember(visualMediaList.size) {
        derivedStateOf {
            when (visualMediaList.size) {
                1 -> 1
                2, 3 -> visualMediaList.size
                else -> 4
            }
        }
    }
    
    val limitedMediaList by remember(visualMediaList, maxItems) {
        derivedStateOf { visualMediaList.take(maxItems) }
    }

    val gridState = rememberLazyGridState()

    // Optimize: Pre-compute aspect ratios once instead of on every recomposition
    // This function is NOT @Composable to avoid unnecessary recomposition checks
    // Matches iOS implementation for consistent cross-platform media display
    fun aspectRatioOf(item: MimeiFileType): Float {
        val itemType = inferMediaTypeFromAttachment(item)
        return when (itemType) {
            MediaType.Video, MediaType.HLS_VIDEO -> {
                // For videos, try to get aspect ratio from stored value first
                // Default to 16:9 (standard video format) to match iOS implementation
                item.aspectRatio?.takeIf { it > 0 } ?: (16f / 9f)
            }
            MediaType.Image -> {
                // Match iOS MediaGridViewModel.getAspectRatio:
                // unknown image aspect ratio falls back to 1:1 to keep layout stable.
                item.aspectRatio?.takeIf { it > 0 } ?: 1f
            }
            else -> {
                // Stable default for non-image/video attachments.
                1f
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

    fun isAutoPlayForGridIndex(gridIndex: Int): Boolean {
        // Only autoplay the first video in the grid
        return firstVideoIndex >= 0 && gridIndex == firstVideoIndex
    }
    
    // Preload videos and images with limited concurrency to avoid thread pool contention
    LaunchedEffect(limitedMediaList, enableRowPreloading) {
        if (!enableRowPreloading) return@LaunchedEffect
        // Delay preloading so fast-scrolling cancels before starting heavy work
        kotlinx.coroutines.delay(300L)
        val preloadSemaphore = kotlinx.coroutines.sync.Semaphore(2) // Max 2 concurrent preloads
        limitedMediaList.forEach { item ->
            val mediaType = inferMediaTypeFromAttachment(item)
            val mediaUrl = getMediaUrl(item.mid, tweet.author?.baseUrl.orEmpty()).toString()
            when {
                (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) &&
                        !VideoManager.isVideoPreloaded(item.mid) -> {
                    launch(Dispatchers.IO) {
                        preloadSemaphore.acquire()
                        try {
                            VideoManager.preloadVideo(context, item.mid, mediaUrl, item.type)
                        } catch (e: Exception) {
                            Timber.tag("MediaGrid").e(e, "Failed to preload video: ${item.mid}")
                        } finally {
                            preloadSemaphore.release()
                        }
                    }
                }
                mediaType == MediaType.Image -> {
                    launch(Dispatchers.IO) {
                        preloadSemaphore.acquire()
                        try {
                            ImageCacheManager.preloadImages(context, item.mid, mediaUrl)
                        } catch (e: Exception) {
                            Timber.tag("MediaGrid").e(e, "Failed to preload image: ${item.mid}")
                        } finally {
                            preloadSemaphore.release()
                        }
                    }
                }
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
                .heightIn(max = 400.dp)
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
                                visualMediaList.map {
                                    MediaItem(
                                        getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                        it.type
                                    )
                                }, 0, tweet.mid, tweet.authorId
                            )
                            if (params.mediaItems.getOrNull(0)?.type == MediaType.Video ||
                                params.mediaItems.getOrNull(0)?.type == MediaType.HLS_VIDEO
                            ) {
                                if (enableCoordinator) {
                                    videoCoordinator.syncToFullScreenPlayer()
                                    videoCoordinator.stopAllVideos()
                                } else {
                                    FullScreenPlayerManager.setVideoListFromMediaItems(params.mediaItems, params.index)
                                }
                                visualMediaList.getOrNull(0)?.mid?.let { tappedVideoMid ->
                                    VideoManager.suspendFeedActivityForFullScreen(tappedVideoMid)
                                    VideoManager.pauseVideo(tappedVideoMid)
                                }
                            }
                            navController.navigate(NavTweet.MediaViewer(params))
                        },
                    index = 0,
                    numOfHiddenItems = if (visualMediaList.size > maxItems) visualMediaList.size - maxItems else 0,
                    autoPlay = isAutoPlayForGridIndex(0),
                    inPreviewGrid = true,
                    loadOriginalImage = true, // Load original high-res image for single image
                    viewModel = viewModel,
                    parentTweetId = parentTweetId,
                    enableCoordinator = enableCoordinator,
                    containerTopY = containerTopY,
                    allMediaItems = visualMediaList // Pass all visual items for full screen navigation
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
                val safeAr0 = ar0.coerceAtLeast(0.01f)
                val safeAr1 = ar1.coerceAtLeast(0.01f)

                if (isLandscape0 && isLandscape1) {
                    // iOS parity: both landscape -> vertical split, grid AR 0.8,
                    // heights proportional to ideal heights (inverse aspect ratio).
                    val weight0 = 1f / safeAr0
                    val weight1 = 1f / safeAr1
                    val totalWeight = weight0 + weight1
                    val normalizedWeight0 = weight0 / totalWeight
                    val normalizedWeight1 = weight1 / totalWeight

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(normalizedWeight0)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(normalizedWeight1)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
                        }
                    }
                } else if (isPortrait0 && isPortrait1) {
                    // iOS parity: both portrait -> horizontal split, grid AR 1.0,
                    // widths proportional to ideal widths (aspect ratios).
                    val totalIdealWidth = safeAr0 + safeAr1
                    val weight0 = safeAr0 / totalIdealWidth
                    val weight1 = safeAr1 / totalIdealWidth

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(weight0)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(weight1)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
                        }
                    }
                } else {
                    // iOS parity: mixed -> horizontal split, grid AR clamped(ar0 + ar1, 0.8...1.618),
                    // widths proportional to ideal widths.
                    val totalIdealWidth = safeAr0 + safeAr1
                    val gridAspectRatio = totalIdealWidth.coerceIn(0.8f, 1.618f)
                    val weight0 = safeAr0 / totalIdealWidth
                    val weight1 = safeAr1 / totalIdealWidth

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(gridAspectRatio),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(weight0)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(weight1)
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
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                                allMediaItems = visualMediaList
                            )
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
                val minHeroWeight = 0.618f // Golden ratio floor (matches iOS mixed-layout logic)
                val safeAr0 = ar0.coerceAtLeast(0.01f)
                val safeAr1 = ar1.coerceAtLeast(0.01f)
                val safeAr2 = ar2.coerceAtLeast(0.01f)

                if (allPortrait) {
                    // iOS: hero fixed to golden ratio on the left; right stack split by ideal heights.
                    val weight1 = 1f / safeAr1
                    val weight2 = 1f / safeAr2
                    val totalWeight = weight1 + weight2
                    val normalizedWeight1 = weight1 / totalWeight
                    val normalizedWeight2 = weight2 / totalWeight
                    val heroWeight = minHeroWeight
                    val sideWeight = 1f - heroWeight
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .weight(heroWeight)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: right side (38.2%), stacked with proportional heights
                        Column(
                            modifier = Modifier.weight(sideWeight),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight1)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight2)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else if (allLandscape) {
                    // iOS: hero fixed to golden ratio on top; bottom row split by ideal widths.
                    val totalIdealWidth = safeAr1 + safeAr2
                    val weight1 = ar1 / totalIdealWidth
                    val weight2 = ar2 / totalIdealWidth
                    val heroWeight = minHeroWeight
                    val bottomWeight = 1f - heroWeight
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(heroWeight)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: bottom (38.2%), side by side with proportional widths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bottomWeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(weight1)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(weight2)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else if (isPortrait0) {
                    // iOS mixed portrait case:
                    // left hero = max(proportional split, golden-ratio floor), right side stacked proportionally.
                    val rightIdealWidth = maxOf(safeAr1, safeAr2)
                    val proportionalHeroWeight = safeAr0 / (safeAr0 + rightIdealWidth)
                    val heroWeight = proportionalHeroWeight.coerceAtLeast(minHeroWeight).coerceAtMost(0.9f)
                    val sideWeight = 1f - heroWeight

                    val weight1 = 1f / safeAr1
                    val weight2 = 1f / safeAr2
                    val totalWeight = weight1 + weight2
                    val normalizedWeight1 = weight1 / totalWeight
                    val normalizedWeight2 = weight2 / totalWeight

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: left 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .weight(heroWeight)
                                .fillMaxHeight()
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: right side (38.2%), stacked with proportional heights
                        Column(
                            modifier = Modifier.weight(sideWeight),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight1)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(normalizedWeight2)
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                } else {
                    // iOS mixed landscape case:
                    // top hero = max(proportional split, golden-ratio floor), bottom split proportionally.
                    val bottomIdealHeight = maxOf(1f / safeAr1, 1f / safeAr2)
                    val proportionalHeroWeight = (1f / safeAr0) / ((1f / safeAr0) + bottomIdealHeight)
                    val heroWeight = proportionalHeroWeight.coerceAtLeast(minHeroWeight).coerceAtMost(0.9f)
                    val bottomWeight = 1f - heroWeight

                    val totalIdealWidth = safeAr1 + safeAr2
                    val weight1 = safeAr1 / totalIdealWidth
                    val weight2 = safeAr2 / totalIdealWidth

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        // First image: top 61.8% (golden ratio)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(heroWeight)
                                .clipToBounds()
                        ) {
                            MediaItemView(
                                limitedMediaList,
                                modifier = Modifier.fillMaxSize(),
                                index = 0,
                                autoPlay = isAutoPlayForGridIndex(0),
                                inPreviewGrid = true,
                                viewModel = viewModel,
                                parentTweetId = parentTweetId,
                                enableCoordinator = enableCoordinator,
                                containerTopY = containerTopY,
                            )
                        }
                        // Second and third: bottom (38.2%), side by side with proportional widths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(bottomWeight),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(weight1)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 1,
                                    autoPlay = isAutoPlayForGridIndex(1),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(weight2)
                                    .fillMaxHeight()
                                    .clipToBounds()
                            ) {
                                MediaItemView(
                                    limitedMediaList,
                                    modifier = Modifier.fillMaxSize(),
                                    index = 2,
                                    autoPlay = isAutoPlayForGridIndex(2),
                                    inPreviewGrid = true,
                                    viewModel = viewModel,
                                    parentTweetId = parentTweetId,
                                    enableCoordinator = enableCoordinator,
                                    containerTopY = containerTopY,
                                )
                            }
                        }
                    }
                }
            }
            4 -> {
                // Use cached aspect ratios to determine grid aspect ratio
                val ar0 = cachedAspectRatios[0]
                val ar1 = cachedAspectRatios[1]
                val ar2 = cachedAspectRatios[2]
                val ar3 = cachedAspectRatios[3]
                val allLandscape = ar0 > 1f && ar1 > 1f && ar2 > 1f && ar3 > 1f
                val allPortrait = ar0 < 1f && ar1 < 1f && ar2 < 1f && ar3 < 1f
                
                val gridAspectRatio = when {
                    allLandscape -> 1.618f
                    allPortrait -> 0.8f
                    else -> 1f
                }
                
                // Use improved lazy grid method for 4+ items with better loading optimization
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(gridAspectRatio)
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    itemsIndexed(
                        items = limitedMediaList,
                        key = { index, item -> "${item.mid}_$index" } // Stable key for better performance
                    ) { index, mediaItem ->
                        // Use grid aspect ratio for items when all have same orientation, otherwise use square
                        val itemAspectRatio = gridAspectRatio
                        MediaItemView(
                            limitedMediaList,
                            modifier = Modifier
                                .aspectRatio(itemAspectRatio)
                                .clipToBounds()
                                .clickable {
                                    val params = MediaViewerParams(
                                        visualMediaList.map {
                                            MediaItem(
                                                getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                                it.type
                                            )
                                        }, index, tweet.mid, tweet.authorId
                                    )
                                    if (params.mediaItems.getOrNull(index)?.type == MediaType.Video ||
                                        params.mediaItems.getOrNull(index)?.type == MediaType.HLS_VIDEO
                                    ) {
                                        if (enableCoordinator) {
                                            videoCoordinator.syncToFullScreenPlayer()
                                            videoCoordinator.stopAllVideos()
                                        } else {
                                            FullScreenPlayerManager.setVideoListFromMediaItems(params.mediaItems, params.index)
                                        }
                                        visualMediaList.getOrNull(index)?.mid?.let { tappedVideoMid ->
                                            VideoManager.suspendFeedActivityForFullScreen(tappedVideoMid)
                                            VideoManager.pauseVideo(tappedVideoMid)
                                        }
                                    }
                                    navController.navigate(NavTweet.MediaViewer(params))
                                },
                            index = index,
                            numOfHiddenItems = if (index == limitedMediaList.size - 1 && visualMediaList.size > maxItems)
                                visualMediaList.size - maxItems else 0,
                            autoPlay = isAutoPlayForGridIndex(index),
                            inPreviewGrid = true,
                            viewModel = viewModel,
                            parentTweetId = parentTweetId,
                            enableCoordinator = enableCoordinator,
                            containerTopY = containerTopY,
                            allMediaItems = visualMediaList // Pass all visual items for full screen navigation
                        )
                    }
                }
            }
            }

            // Mute button anchored to the MediaCell's bottom-end (the heightIn-capped outer
            // Box), not to the video frame. Shown whenever any video item is in the cell.
            // Mute is a global preference, so a single overlay covers multi-video grids too.
            val hasVideo = limitedMediaList.any {
                it.type == MediaType.Video || it.type == MediaType.HLS_VIDEO
            }
            if (hasVideo) {
                val isMuted by preferenceHelper.speakerMuteFlow.collectAsState(
                    initial = preferenceHelper.getSpeakerMute()
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { preferenceHelper.setSpeakerMute(!isMuted) }
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Remaining-time label anchored to the MediaCell's bottom-start. Shown only
            // when the cell holds a single media item AND that item is a video — matches
            // the iOS rule. Reads position from the cached ExoPlayer via VideoManager and
            // polls every second while the player exists.
            val soleVideoMid = limitedMediaList.singleOrNull()
                ?.takeIf { it.type == MediaType.Video || it.type == MediaType.HLS_VIDEO }
                ?.mid
            if (soleVideoMid != null) {
                val soleVideoPlaybackId = if (enableCoordinator) {
                    videoPlaybackIdentifier(
                        videoMid = soleVideoMid,
                        parentTweetId = parentTweetId?.takeIf { it.isNotEmpty() } ?: tweet.mid
                    )
                } else {
                    soleVideoMid
                }
                var remainingMs by remember(soleVideoPlaybackId, soleVideoMid) { mutableLongStateOf(0L) }
                LaunchedEffect(soleVideoPlaybackId, soleVideoMid) {
                    while (true) {
                        val player = VideoManager.getCachedVideoPlayer(soleVideoPlaybackId)
                            ?: VideoManager.getCachedVideoPlayer(soleVideoMid)
                        if (player != null) {
                            val dur = player.duration
                            val pos = player.currentPosition
                            if (dur > 0) {
                                remainingMs = (dur - pos).coerceAtLeast(0)
                            }
                        } else {
                            remainingMs = 0L
                        }
                        delay(1000)
                    }
                }
                if (remainingMs > 0) {
                    val totalSeconds = remainingMs / 1000
                    val mm = totalSeconds / 60
                    val ss = (totalSeconds % 60).toString().padStart(2, '0')
                    Text(
                        text = "$mm:$ss",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 0.dp)
                    )
                }
            }
        }

        if (audioMediaList.isNotEmpty()) {
            SimpleMp3PlaylistPlayer(
                attachments = audioMediaList,
                baseUrl = tweet.author?.baseUrl.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (limitedMediaList.isNotEmpty()) 8.dp else 0.dp)
            )
        }

        if (documentMediaList.isNotEmpty()) {
            DocumentAttachmentsView(
                documents = documentMediaList,
                baseUrl = tweet.author?.baseUrl,
                maxDocuments = 2,
                modifier = Modifier.padding(top = if (limitedMediaList.isNotEmpty() || audioMediaList.isNotEmpty()) 4.dp else 0.dp)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SimpleMp3PlaylistPlayer(
    attachments: List<MimeiFileType>,
    baseUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolvedAttachments = remember(attachments, baseUrl) {
        attachments.map { attachment ->
            attachment.copy(
                url = attachment.url?.takeIf { it.isNotBlank() }
                    ?: getMediaUrl(attachment.mid, baseUrl).toString()
            )
        }
    }
    val mediaItems = remember(resolvedAttachments) {
        resolvedAttachments.mapNotNull { attachment ->
            attachment.url?.let { androidx.media3.common.MediaItem.fromUri(it) }
        }
    }
    if (mediaItems.isEmpty()) return

    val exoPlayer = remember { createAudioExoPlayer(context) }
    var currentIndex by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playlistExpanded by remember { mutableStateOf(false) }
    var isPlaybackLoading by remember { mutableStateOf(false) }
    var playbackLoadFailed by remember { mutableStateOf(false) }
    var lastAudioUnavailableToastAtMs by remember { mutableLongStateOf(0L) }
    val audioUnavailableMessage = stringResource(R.string.audio_unavailable)

    fun showAudioUnavailableToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudioUnavailableToastAtMs > 1_000L) {
            lastAudioUnavailableToastAtMs = now
            Toast.makeText(context, audioUnavailableMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun startAudioPlayback(index: Int, resetPosition: Boolean, reloadQueue: Boolean = false) {
        val targetIndex = index.coerceIn(0, mediaItems.lastIndex)
        val canResumeCurrentTrack = !reloadQueue &&
            !resetPosition &&
            !playbackLoadFailed &&
            exoPlayer.playerError == null &&
            exoPlayer.playbackState == Player.STATE_READY &&
            exoPlayer.currentMediaItemIndex == targetIndex

        currentIndex = targetIndex
        playbackLoadFailed = false

        if (canResumeCurrentTrack) {
            isPlaybackLoading = false
            exoPlayer.play()
            return
        }

        positionMs = 0L
        durationMs = 0L
        exoPlayer.stop()
        exoPlayer.setMediaItems(mediaItems, targetIndex, 0L)
        isPlaybackLoading = true
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(mediaItems) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentIndex = 0
        positionMs = 0L
        durationMs = 0L
        isPlaying = false
        isPlaybackLoading = false
        playbackLoadFailed = false
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                if (isPlayingNow) {
                    isPlaybackLoading = false
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                currentIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                if (exoPlayer.playWhenReady && playbackState == Player.STATE_BUFFERING) {
                    isPlaybackLoading = true
                }
                if (playbackState == Player.STATE_READY) {
                    isPlaybackLoading = false
                }
                if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    isPlaybackLoading = false
                    positionMs = 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.tag("SimpleMp3PlaylistPlayer").w(
                    error,
                    "Audio playback failed index=$currentIndex state=${exoPlayer.playbackState} " +
                        "url=${resolvedAttachments.getOrNull(currentIndex)?.url}"
                )
                playbackLoadFailed = true
                isPlaying = false
                isPlaybackLoading = false
                exoPlayer.playWhenReady = false
                showAudioUnavailableToast()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying, currentIndex) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            delay(500L)
        }
    }

    val currentTrack = resolvedAttachments.getOrNull(currentIndex)
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val playerBackground = MaterialTheme.colorScheme.secondaryContainer
    val playerContent = MaterialTheme.colorScheme.onSecondaryContainer
    val playerMutedContent = playerContent.copy(alpha = 0.72f)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        color = playerBackground
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { playlistExpanded = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentTrack?.fileName ?: currentTrack?.mid ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (resolvedAttachments.size > 1) {
                        Text(
                            text = "${currentIndex + 1}/${resolvedAttachments.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = playerMutedContent,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = { playlistExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Open audio playlist",
                            tint = playerContent
                        )
                    }
                }
                DropdownMenu(
                    expanded = playlistExpanded,
                    onDismissRequest = { playlistExpanded = false }
                ) {
                    resolvedAttachments.forEachIndexed { index, attachment ->
                        val selected = index == currentIndex
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = attachment.fileName ?: attachment.mid,
                                    style = if (selected) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                playlistExpanded = false
                                currentIndex = index
                                positionMs = 0L
                                durationMs = 0L
                                isPlaying = false
                                isPlaybackLoading = false
                                playbackLoadFailed = false
                                exoPlayer.stop()
                                exoPlayer.clearMediaItems()
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val previous = if (currentIndex <= 0) mediaItems.lastIndex else currentIndex - 1
                        startAudioPlayback(previous, resetPosition = true, reloadQueue = true)
                    },
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous track",
                        tint = playerContent,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            isPlaybackLoading = false
                        } else {
                            startAudioPlayback(
                                currentIndex,
                                resetPosition = playbackLoadFailed || exoPlayer.playbackState == Player.STATE_ENDED
                            )
                        }
                    },
                    modifier = Modifier.size(60.dp)
                ) {
                    if (isPlaybackLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = playerContent,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = playerContent,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val next = if (currentIndex >= mediaItems.lastIndex) 0 else currentIndex + 1
                        startAudioPlayback(next, resetPosition = true, reloadQueue = true)
                    },
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next track",
                        tint = playerContent,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatPlaybackTime(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = playerMutedContent
                )
                Text(
                    text = formatPlaybackTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = playerMutedContent
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

private fun formatPlaybackTime(timeMs: Long): String {
    val safeTime = timeMs.coerceAtLeast(0L)
    val totalSeconds = safeTime / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
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
